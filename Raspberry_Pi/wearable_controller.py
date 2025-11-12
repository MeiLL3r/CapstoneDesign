#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import firebase_admin
from firebase_admin import credentials, db
import serial
import time
import json
import os
import atexit
import threading
import socket

# --- 설정 (Constants) ---
CONFIG_FILE = 'config.json'
FIREBASE_KEY_FILE = 'firebase-key.json'
ARDUINO_PORT = '/dev/ttyACM0'  # 환경에 따라 /dev/ttyUSB0 등으로 변경
BAUD_RATE = 9600
HEARTBEAT_INTERVAL = 3  # 하트비트 전송 간격 (초)

# --- 전역 변수 ---
device_id = None
config_data = {}
firebase_app = None
arduino = None
listener = None
firebase_is_connected = False
main_loop_running = True  # 스레드 종료를 위한 플래그

# --- 1. 최초 실행 시 설정 및 config.json 생성 ---
def validate_and_load_config(config_path):
    global config_data, device_id
    print("설정 파일을 검증하고 로드합니다...")
    try:
        with open(config_path, 'r') as f:
            config_data = json.load(f)
        
        # 필수 키 검증
        required_keys = ['device_id', 'device_name', 'sensors_config', 'default_preset', 'presets']
        if not all(key in config_data for key in required_keys):
            raise ValueError("필수 키가 누락되었습니다.")
            
        # presets 구조 검증
        if not isinstance(config_data['presets'], dict) or not config_data['presets']:
            raise ValueError("'presets'가 비어있거나 잘못된 형식입니다.")
            
        device_id = config_data.get('device_id')
        if not device_id:
            raise ValueError("'device_id'가 비어있습니다.")
            
        print("✅ 설정 파일 검증 완료.")
        return True
    except (json.JSONDecodeError, ValueError, KeyError) as e:
        print(f"⚠️ 설정 파일이 손상되었거나 유효하지 않습니다: {e}")
        # 손상된 파일 백업
        corrupted_path = config_path + ".corrupted"
        if os.path.exists(config_path):
            os.rename(config_path, corrupted_path)
            print(f"손상된 설정 파일을 '{corrupted_path}'로 백업했습니다.")
        return False

def setup_device_and_config():
    global device_id, config_data
    print("--- 최초 설정 모드 ---")
    device_id = input("기기 고유번호를 입력하세요 (예: 123): ").strip()
    device_name = input(f"'{device_id}' 기기의 별명을 입력하세요 (예: 내 작업복): ").strip()

    if not device_id or not device_name:
        print("오류: 기기 고유번호와 별명은 반드시 입력해야 합니다.")
        exit()

    # 기본 센서 설정 데이터 (물리적 정보)
    default_sensors_config = {
        'sensor_01': {'name': '왼쪽 팔', 'posX': 0.15, 'posY': 0.45},
        'sensor_02': {'name': '가슴 중앙', 'posX': 0.5, 'posY': 0.3},
        'sensor_03': {'name': '등 중앙', 'posX': 0.5, 'posY': 0.5},
        'sensor_04': {'name': '오른쪽 팔', 'posX': 0.85, 'posY': 0.45}
    }
    
    default_preset_sensors = {
        'sensor_01': {'mode': 'cooling', 'target_temp': 22},
        'sensor_02': {'mode': 'cooling', 'target_temp': 24},
        'sensor_03': {'mode': 'off', 'target_temp': 0},
        'sensor_04': {'mode': 'off', 'target_temp': 0}
    }

    # config.json에 저장할 데이터 구성
    config_data = {
        'device_id': device_id,
        'device_name': device_name,
        'sensors_config': default_sensors_config,
        'default_preset': 'preset_daily',
        'presets': {
            'preset_daily': {
                'name': '일상 모드',
                'sensors': default_preset_sensors
            }
        }
    }

    # 설정 파일 저장
    script_dir = os.path.dirname(os.path.abspath(__file__))
    config_path = os.path.join(script_dir, CONFIG_FILE)
    with open(config_path, 'w') as f:
        json.dump(config_data, f, indent=4)
    print(f"✅ 설정 파일 '{config_path}' 생성 완료.")
    
    # Firebase에 초기 데이터 업로드
    upload_initial_config_to_firebase()

def upload_initial_config_to_firebase():
    print("Firebase에 완전한 초기 데이터 구조를 생성합니다...")
    if not device_id or not config_data:
        print("오류: 기기 ID 또는 설정 데이터가 없습니다.")
        return

    try:
        ref = db.reference(f'devices/{device_id}', app=firebase_app)
        
        # 센서 config에서 temp 필드 추가 후 status 데이터 생성
        status_sensors = {
            sensor_id: {**info, 'temp': 0} for sensor_id, info in config_data['sensors_config'].items()
        }
        
        # config_data를 기반으로 Firebase 데이터 구조 생성
        ref.set({
            'name': config_data['device_name'], # <-- name 필드 추가
            'default_preset': config_data['default_preset'],
            'connection': {
                'status': 'offline',
                'last_seen': 0
            },
            'control': {
                'preset_applied': config_data['default_preset'],
                'sensors': config_data['presets'][config_data['default_preset']]['sensors'] # <-- control/sensors 구조 추가
            },
            'status': {
                'current_temp': 0,
                'sensors': status_sensors
            },
            'presets': config_data['presets']
        })
        print("✅ Firebase 데이터 셋업 완료.")
    except Exception as e:
        print(f"❌ Firebase 셋업 실패: {e}")

# --- 2. Firebase와 config.json 동기화 ---
def sync_config_with_firebase():
    global config_data
    if not firebase_is_connected:
        print("동기화 실패: Firebase에 연결되지 않았습니다.")
        return

    print("🔄 설정 동기화(프리셋 포함)를 시작합니다...")
    try:
        device_ref = db.reference(f"devices/{device_id}", app=firebase_app)
        firebase_data = device_ref.get()

        if firebase_data is None:
            print("Firebase에 기기 데이터가 없습니다. 로컬 설정을 업로드합니다.")
            upload_initial_config_to_firebase()
            return
            
        firebase_presets = firebase_data.get('presets', {})
        local_presets = config_data.get('presets', {})
        updated = False

        # Firebase -> 로컬 동기화
        for preset_id, info in firebase_presets.items():
            if preset_id not in local_presets or local_presets[preset_id] != info:
                local_presets[preset_id] = info
                updated = True
        
        # 로컬 -> Firebase 동기화
        for preset_id, info in local_presets.items():
            if preset_id not in firebase_presets:
                device_ref.child('presets').child(preset_id).set(info)
                # 'updated'는 로컬 파일 변경 여부이므로 여기서는 false

        # default_preset 동기화 (Firebase 우선)
        firebase_default = firebase_data.get('default_preset')
        if firebase_default and config_data.get('default_preset') != firebase_default:
            config_data['default_preset'] = firebase_default
            updated = True

        if updated:
            print("프리셋 정보가 동기화되어 config.json을 업데이트합니다.")
            script_dir = os.path.dirname(os.path.abspath(__file__))
            config_path = os.path.join(script_dir, CONFIG_FILE)
            with open(config_path, 'w') as f:
                json.dump(config_data, f, indent=4)
        
        print("✅ 설정 동기화 완료.")
    except Exception as e:
        print(f"❌ 설정 동기화 중 오류 발생: {e}")

# --- 3. Firebase 통신 (백그라운드 스레드) ---
def firebase_thread_worker():
    global firebase_app, firebase_is_connected, listener
    
    last_heartbeat_time = 0
    while main_loop_running:
        if not firebase_is_connected:
            try:
                print("Firebase 연결을 시도합니다...")
                script_dir = os.path.dirname(os.path.abspath(__file__))
                key_path = os.path.join(script_dir, FIREBASE_KEY_FILE)
                cred = credentials.Certificate(key_path)
                database_url = f'https://{cred.project_id}-default-rtdb.firebaseio.com/'
                
                if not firebase_admin._apps:
                    firebase_app = firebase_admin.initialize_app(cred, {'databaseURL': database_url})
                else:
                    firebase_app = firebase_admin.get_app()
                
                print("✅ Firebase 초기화 성공.")
                firebase_is_connected = True
                
                set_connection_status("online")
                sync_config_with_firebase()
                setup_firebase_listeners()

            except (socket.gaierror, IOError, ValueError, FileNotFoundError) as e:
                print(f"❌ Firebase 연결 실패: {e}. 10초 후 재시도합니다.")
                firebase_is_connected = False
                time.sleep(10)
        else:
            current_time = time.time()
            if current_time - last_heartbeat_time > HEARTBEAT_INTERVAL:
                try:
                    connection_ref = db.reference(f'devices/{device_id}/connection', app=firebase_app)
                    local_timestamp_ms = int(time.time() * 1000)
                    connection_ref.child('last_seen').set(local_timestamp_ms) 
                    print("❤️  하트비트 전송 (last_seen 업데이트).")
                    last_heartbeat_time = current_time
                except Exception as e:
                    print(f"하트비트 전송 실패, 연결을 재설정합니다: {e}")
                    firebase_is_connected = False
            time.sleep(1)

def set_connection_status(status):
    if firebase_is_connected and device_id:
        try:
            connection_ref = db.reference(f'devices/{device_id}/connection', app=firebase_app)
            local_timestamp_ms = int(time.time() * 1000)

            connection_ref.update({
                'status': status,
                'last_seen': local_timestamp_ms
            })
            print(f"✅ Firebase 연결 상태 '{status}'로 설정 완료.")
        except Exception as e:
            print(f"연결 상태 '{status}' 설정 실패: {e}")

def setup_firebase_listeners():
    global listener
    print("Firebase 리스너 설정을 시작합니다.")
    control_ref = db.reference(f'devices/{device_id}/control/sensors', app=firebase_app)
    listener = control_ref.listen(control_listener)
    print("📡 Firebase 제어 데이터 감시 시작...")

def control_listener(event):
    print(f"🔥 Firebase 제어 데이터 감지: 경로({event.path}), 데이터({event.data})")
    
    path_parts = event.path.strip("/").split("/")
    if len(path_parts) == 2:
        sensor_id, key = path_parts
        value = event.data
        
        # sensor_01 -> 1, sensor_02 -> 2
        try:
            sensor_index = int(sensor_id.split('_')[-1])
        except (ValueError, IndexError):
            print(f"잘못된 센서 ID 형식: {sensor_id}")
            return

        if arduino and arduino.is_open:
            if key == 'mode':
                command = f"MODE:{sensor_index}:{str(value).upper()}\n"
                arduino.write(command.encode())
                print(f"-> 아두이노 전송: {command.strip()}")
            elif key == 'target_temp':
                command = f"TEMP:{sensor_index}:{value}\n"
                arduino.write(command.encode())
                print(f"-> 아두이노 전송: {command.strip()}")

def apply_preset_to_arduino(preset_id):
    if preset_id in config_data.get('presets', {}):
        print(f"프리셋 '{preset_id}'를 아두이노에 적용합니다...")
        preset_sensors = config_data['presets'][preset_id]['sensors']
        for sensor_id, settings in preset_sensors.items():
            try:
                sensor_index = int(sensor_id.split('_')[-1])
                mode = settings['mode']
                temp = settings['target_temp']
                
                if arduino and arduino.is_open:
                    mode_command = f"MODE:{sensor_index}:{mode.upper()}\n"
                    temp_command = f"TEMP:{sensor_index}:{temp}\n"
                    arduino.write(mode_command.encode())
                    time.sleep(0.05) # 아두이노 버퍼를 위한 짧은 딜레이
                    arduino.write(temp_command.encode())
                    time.sleep(0.05)
                    print(f" -> {sensor_id}: {mode.upper()}, {temp}°C 전송")
            except (ValueError, IndexError):
                print(f"잘못된 센서 ID 형식: {sensor_id}")
    else:
        print(f"경고: '{preset_id}' 프리셋을 찾을 수 없습니다.")

# --- 4. 아두이노 통신 (백그라운드 스레드) ---
def arduino_thread_worker():
    global arduino
    while main_loop_running:
        try:
            if arduino is None or not arduino.is_open:
                print("아두이노 연결을 시도합니다...")
                arduino = serial.Serial(ARDUINO_PORT, BAUD_RATE, timeout=1)
                time.sleep(2)
                print(f"✅ 아두이노 연결 성공 ({ARDUINO_PORT})")

            if arduino.in_waiting > 0:
                line = arduino.readline().decode('utf-8').strip()
                if line.startswith("SENSORS:") and firebase_is_connected:
                    try:
                        parts = line.split(":")[1].split(",")
                        if len(parts) == len(config_data['sensors_config']):
                            temps = [int(p) for p in parts]
                            print(f"<- 아두이노 수신: {temps}")
                            
                            avg_temp = sum(temps) // len(temps)
                            status_ref = db.reference(f'devices/{device_id}/status', app=firebase_app)
                            updates = {'current_temp': avg_temp}
                            # config.json에 정의된 센서 ID 순서대로 매핑
                            for i, sensor_id in enumerate(config_data['sensors_config']):
                                updates[f'sensors/{sensor_id}/temp'] = temps[i]
                            status_ref.update(updates)
                    except Exception as e:
                        print(f"아두이노 데이터 처리 중 오류: {e}")

        except serial.SerialException as e:
            print(f"⚠️ 아두이노 연결 실패: {e}. 5초 후 재시도합니다.")
            if arduino: arduino.close()
            arduino = None
            time.sleep(5)
        except Exception as e:
            print(f"아두이노 통신 중 심각한 오류: {e}")
        time.sleep(0.1)

# --- 5. 프로그램 종료 처리 ---
def cleanup():
    global main_loop_running, listener, arduino
    if not main_loop_running:
        return  # 이미 종료 절차가 시작되었으면 중복 실행 방지
        
    print("\n--- 최후의 종료 처리 시작 (atexit) ---")
    main_loop_running = False # 모든 스레드에 종료 신호

    if listener:
        try:
            listener.close()
        except Exception:
            pass # 오류가 나도 무시

    if arduino and arduino.is_open:
        arduino.close()
    
    print("--- 종료 처리 완료 ---")

atexit.register(cleanup)

# --- 6. 메인 로직 ---
def main():
    global device_id, config_data, firebase_app, arduino, main_loop_running
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    config_path = os.path.join(script_dir, CONFIG_FILE)
    
    # 백그라운드 스레드 객체를 미리 선언
    firebase_thread = None
    arduino_thread = None
    
    try:
        if not os.path.exists(config_path) or not validate_and_load_config(config_path):
            print("최초 설정이 필요합니다.")
            try:
                key_path = os.path.join(script_dir, FIREBASE_KEY_FILE)
                cred = credentials.Certificate(key_path)
                database_url = f'https://{cred.project_id}-default-rtdb.firebaseio.com/'
                firebase_app = firebase_admin.initialize_app(cred, {'databaseURL': database_url})
                setup_device_and_config()
            except Exception as e:
                print(f"❌ 최초 설정 중 치명적 오류: {e}"); return # 함수 종료
        
        print(f"--- 기기 {device_id} 컨트롤러 시작 ---")

        try:
            print("기본 프리셋 적용을 위해 아두이노 연결을 시도합니다...")
            arduino = serial.Serial(ARDUINO_PORT, BAUD_RATE, timeout=1)
            time.sleep(2)
            print(f"✅ 아두이노 연결 성공 ({ARDUINO_PORT})")
            default_preset_id = config_data.get('default_preset')
            if default_preset_id:
                apply_preset_to_arduino(default_preset_id)
        except serial.SerialException as e:
            print(f"⚠️ 아두이노 초기 연결 실패: {e}. 스레드에서 재시도합니다.")
            arduino = None

        firebase_thread = threading.Thread(target=firebase_thread_worker)
        arduino_thread = threading.Thread(target=arduino_thread_worker)
        
        firebase_thread.start()
        arduino_thread.start()
        
        while main_loop_running:
            time.sleep(1)
            if not firebase_thread.is_alive() or not arduino_thread.is_alive():
                print("오류: 백그라운드 스레드 중 하나가 예기치 않게 종료되었습니다.")
                main_loop_running = False

    except KeyboardInterrupt:
        print("\nCtrl+C 감지. 프로그램을 종료합니다.")
    finally:
        # 1. 모든 스레드에 종료 신호를 보냅니다.
        main_loop_running = False
        print("백그라운드 스레드 종료를 기다리는 중...")
        
        # 2. 스레드가 종료될 때까지 기다립니다.
        if firebase_thread and firebase_thread.is_alive():
            firebase_thread.join(timeout=5)
        if arduino_thread and arduino_thread.is_alive():
            arduino_thread.join(timeout=5)
        
        # 3. 모든 스레드가 종료된 '후'에 리소스를 해제합니다.
        if listener:
            print("Firebase 리스너를 종료합니다...")
            listener.close()
        if arduino and arduino.is_open:
            print("아두이노 연결을 닫습니다...")
            arduino.close()

        # 4. 마지막으로 Firebase 상태를 업데이트합니다.
        if firebase_is_connected:
            # 이 함수는 내부적으로 time.time()을 사용하므로,
            # 종료 시점의 정확한 타임스탬프가 기록됩니다.
            set_connection_status("offline")
            # Firebase와 통신할 시간을 약간 줍니다.
            time.sleep(2)
        
        print("--- 모든 작업이 정상적으로 종료되었습니다. ---")

if __name__ == '__main__':
    main()
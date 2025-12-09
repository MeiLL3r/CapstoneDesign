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
import datetime

# --- 설정 (Constants) ---
CONFIG_FILE = 'config.json'
FIREBASE_KEY_FILE = 'firebase-key.json'
ARDUINO_PORT = '/dev/ttyACM0' 
BAUD_RATE = 9600
HEARTBEAT_INTERVAL = 5  
LOG_INTERVAL = 60 

# --- 전역 변수 ---
device_id = None
config_data = {}
firebase_app = None
arduino = None
listener = None
firebase_is_connected = False
main_loop_running = True  

# --- [추가] 설정을 파일로 저장하는 함수 ---
def save_config_to_file():
    global config_data
    try:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        config_path = os.path.join(script_dir, CONFIG_FILE)
        with open(config_path, 'w', encoding='utf-8') as f:
            json.dump(config_data, f, indent=4, ensure_ascii=False)
        # print("💾 설정 파일(config.json)이 업데이트되었습니다.") # 너무 자주 출력되면 주석 처리
    except Exception as e:
        print(f"❌ 설정 파일 저장 실패: {e}")

# --- 1. 최초 실행 및 로드 ---
def validate_and_load_config(config_path):
    global config_data, device_id
    print("설정 파일을 검증하고 로드합니다...")
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            config_data = json.load(f)
        
        device_id = config_data.get('device_id')
        if not device_id:
            raise ValueError("'device_id'가 비어있습니다.")
            
        return True
    except (json.JSONDecodeError, ValueError, KeyError) as e:
        print(f"⚠️ 설정 파일 오류: {e}")
        return False

def setup_device_and_config():
    global device_id, config_data
    print("--- 최초 설정 모드 ---")
    device_id = input("기기 고유번호를 입력하세요 (예: 123): ").strip()
    device_password = input("비밀번호를 입력하세요 (예: 0000): ").strip()

    if not device_id or not device_password:
        exit()

    # 기본 센서 설정 (5개)
    default_sensors_config = {
        'sensor_01': {'name': '복부 우측', 'posX': 0.24, 'posY': 0.55},
        'sensor_02': {'name': '복부 좌측', 'posX': 0.35, 'posY': 0.55},
        'sensor_03': {'name': '등 좌측', 'posX': 0.65, 'posY': 0.45},
        'sensor_04': {'name': '등 우측', 'posX': 0.77, 'posY': 0.45},
        'sensor_05': {'name': '등 하부', 'posX': 0.71, 'posY': 0.6}
    }
    
    # [변경] 프리셋 구조도 그룹형으로 변경
    default_presets = {
        'preset_daily': {
            'name': '일상 모드',
            'global_mode': 'cooling',
            'groups': {
                'group_1': {'target_temp': 24},
                'group_2': {'target_temp': 24}
            }
        }
    }

    # config.json 구조
    config_data = {
        'device_id': device_id,
        'device_password': device_password,
        'sensors_config': default_sensors_config,
        'default_preset': 'preset_daily',
        'presets': default_presets,
        # [추가] 마지막 제어 상태 저장용
        'last_control_state': {
            'global_mode': 'cooling',
            'groups': {
                'group_1': {'target_temp': 24},
                'group_2': {'target_temp': 24}
            }
        }
    }

    save_config_to_file()
    print(f"✅ 설정 파일 생성 완료.")
    upload_initial_config_to_firebase()

def upload_initial_config_to_firebase():
    print("Firebase 초기화 중...")
    if not device_id or not config_data: return

    try:
        ref = db.reference(f'devices/{device_id}', app=firebase_app)
        
        status_sensors = {
            sensor_id: {**info, 'temp': 0} for sensor_id, info in config_data['sensors_config'].items()
        }
        
        ref.set({
            'password': config_data['device_password'],
            'default_preset': config_data['default_preset'],
            'connection': {'status': 'offline', 'last_seen': 0},
            'control': config_data['last_control_state'], # 저장된 마지막 상태 업로드
            'status': {
                'current_temp': 0,
                'sensors': status_sensors
            },
            'presets': config_data['presets']
        })
        print("✅ Firebase 데이터 업로드 완료.")
    except Exception as e:
        print(f"❌ Firebase 셋업 실패: {e}")

# --- 2. Firebase <-> config.json 양방향 동기화 ---
def sync_config_with_firebase():
    global config_data
    if not firebase_is_connected: return

    print("🔄 설정 동기화 시작 (Firebase -> Local)...")
    try:
        device_ref = db.reference(f"devices/{device_id}", app=firebase_app)
        firebase_data = device_ref.get()

        if firebase_data is None:
            upload_initial_config_to_firebase()
            return
            
        config_updated = False

        # 1. 제어 상태 동기화 (가장 중요: 최신 제어 상태를 로컬에 저장)
        firebase_control = firebase_data.get('control')
        if firebase_control:
            # 로컬 config에 'last_control_state' 키가 없거나 다르면 업데이트
            if config_data.get('last_control_state') != firebase_control:
                config_data['last_control_state'] = firebase_control
                config_updated = True
                print("📥 최신 제어 상태(Control)를 다운로드했습니다.")

        # 2. 프리셋 동기화
        firebase_presets = firebase_data.get('presets', {})
        if config_data.get('presets') != firebase_presets:
            config_data['presets'] = firebase_presets
            config_updated = True
            print("📥 최신 프리셋(Presets)을 다운로드했습니다.")

        # 3. 센서 설정(이름/위치) 동기화는 생략 (보통 하드웨어 고정이므로)
        
        # 변경사항이 있으면 파일 저장
        if config_updated:
            save_config_to_file()
            print("✅ config.json 업데이트 완료.")
            
            # 동기화된 최신 상태를 아두이노에 즉시 적용
            apply_control_to_arduino(config_data['last_control_state'])

    except Exception as e:
        print(f"❌ 설정 동기화 중 오류: {e}")

# --- 3. Firebase 리스너 ---
def setup_firebase_listeners():
    global listener
    control_ref = db.reference(f'devices/{device_id}/control', app=firebase_app)
    listener = control_ref.listen(control_listener)

def control_listener(event):
    if not event.data: return
    # print(f"🔥 제어 변경 감지: {event.path} -> {event.data}")
    
    try:
        # 변경된 전체 데이터를 가져와서 처리
        root_ref = db.reference(f'devices/{device_id}/control', app=firebase_app)
        full_control = root_ref.get()
        
        if full_control:
            # 1. 아두이노로 명령 전송
            apply_control_to_arduino(full_control)
            
            # 2. [핵심] 변경된 상태를 config.json에 저장 (연결 끊김 대비)
            config_data['last_control_state'] = full_control
            save_config_to_file()
            
    except Exception as e:
        print(f"명령 처리 오류: {e}")

# [공통 함수] 제어 데이터를 아두이노 프로토콜로 변환 및 전송
def apply_control_to_arduino(control_data):
    if not control_data: return
    
    try:
        mode = control_data.get('global_mode', 'off').upper()
        temp_g1 = control_data.get('groups', {}).get('group_1', {}).get('target_temp', 24)
        temp_g2 = control_data.get('groups', {}).get('group_2', {}).get('target_temp', 24)

        if arduino and arduino.is_open:
            cmd_a = f"CMD:A:{mode}:{temp_g1}\n"
            cmd_b = f"CMD:B:{mode}:{temp_g2}\n"
            
            arduino.write(cmd_a.encode())
            time.sleep(0.05)
            arduino.write(cmd_b.encode())
            print(f"-> 아두이노 전송: {cmd_a.strip()} / {cmd_b.strip()}")
    except Exception as e:
        print(f"아두이노 전송 실패: {e}")

# --- 4. 백그라운드 스레드들 ---
def firebase_thread_worker():
    global firebase_app, firebase_is_connected, listener
    last_heartbeat_time = 0
    last_log_time = 0 

    while main_loop_running:
        if not firebase_is_connected:
            try:
                print("Firebase 연결 시도...")
                script_dir = os.path.dirname(os.path.abspath(__file__))
                key_path = os.path.join(script_dir, FIREBASE_KEY_FILE)
                cred = credentials.Certificate(key_path)
                database_url = f'https://{cred.project_id}-default-rtdb.firebaseio.com/'
                
                if not firebase_admin._apps:
                    firebase_app = firebase_admin.initialize_app(cred, {'databaseURL': database_url})
                else:
                    firebase_app = firebase_admin.get_app()
                
                print("✅ Firebase 연결 성공.")
                firebase_is_connected = True
                
                set_connection_status("online")
                sync_config_with_firebase() # 연결 직후 최신 데이터 동기화
                setup_firebase_listeners()

            except Exception as e:
                print(f"❌ 연결 실패: {e}. 10초 후 재시도.")
                firebase_is_connected = False
                time.sleep(10)
        else:
            current_time = time.time()
            if current_time - last_heartbeat_time > HEARTBEAT_INTERVAL:
                try:
                    connection_ref = db.reference(f'devices/{device_id}/connection', app=firebase_app)
                    connection_ref.child('last_seen').set(int(time.time() * 1000))
                    last_heartbeat_time = current_time
                except:
                    firebase_is_connected = False
            
            if current_time - last_log_time > LOG_INTERVAL:
                # 로그 저장 로직 (기존과 동일하여 생략 가능하나 유지)
                try:
                    now = datetime.datetime.now()
                    status_ref = db.reference(f'devices/{device_id}/status/sensors', app=firebase_app)
                    data = status_ref.get()
                    if data:
                        log_data = {k: v.get('temp', 0) for k, v in data.items()}
                        db.reference(f'devices/{device_id}/logs/{now.strftime("%Y%m%d")}/{now.strftime("%H%M%S")}', app=firebase_app).set(log_data)
                        last_log_time = current_time
                except: pass
            
            time.sleep(1)

def set_connection_status(status):
    if firebase_is_connected and device_id:
        try:
            db.reference(f'devices/{device_id}/connection', app=firebase_app).update({
                'status': status, 'last_seen': int(time.time() * 1000)
            })
        except: pass

def arduino_thread_worker():
    global arduino
    while main_loop_running:
        try:
            if arduino is None or not arduino.is_open:
                arduino = serial.Serial(ARDUINO_PORT, BAUD_RATE, timeout=1)
                time.sleep(2)
                print(f"✅ 아두이노 연결됨.")
                # 연결되면 로컬에 저장된 마지막 상태를 즉시 전송 (인터넷 없어도 작동)
                if 'last_control_state' in config_data:
                    print("기존 설정 복구 중...")
                    apply_control_to_arduino(config_data['last_control_state'])

            if arduino.in_waiting > 0:
                line = arduino.readline().decode('utf-8').strip()
                if line.startswith("SENSORS:") and firebase_is_connected:
                    try:
                        parts = line.split(":")[1].split(",")
                        if len(parts) == 5:
                            temps = [int(p) for p in parts]
                            avg = sum(temps) // 5
                            updates = {'current_temp': avg}
                            for i in range(5):
                                updates[f'sensors/sensor_{i+1:02d}/temp'] = temps[i]
                            db.reference(f'devices/{device_id}/status', app=firebase_app).update(updates)
                    except: pass
        except:
            arduino = None
            time.sleep(5)
        time.sleep(0.1)

def cleanup():
    global main_loop_running
    main_loop_running = False
    if listener: 
        try: listener.close() 
        except: pass
    if arduino: arduino.close()
    if firebase_is_connected: set_connection_status("offline")
    print("종료됨.")

atexit.register(cleanup)

def main():
    global device_id, config_data, firebase_app, arduino, main_loop_running
    script_dir = os.path.dirname(os.path.abspath(__file__))
    config_path = os.path.join(script_dir, CONFIG_FILE)
    
    # 1. 기존 설정 파일이 있으면 로드, 구조가 다르면 새로 생성 유도
    if not os.path.exists(config_path) or not validate_and_load_config(config_path):
        # 구조 변경을 위해 기존 파일 삭제 후 재설정 권장
        if os.path.exists(config_path):
            print("⚠️ 기존 설정 파일 구조가 다릅니다. 삭제하고 새로 설정합니다.")
            os.remove(config_path)
        
        try:
            key_path = os.path.join(script_dir, FIREBASE_KEY_FILE)
            cred = credentials.Certificate(key_path)
            database_url = f'https://{cred.project_id}-default-rtdb.firebaseio.com/'
            firebase_app = firebase_admin.initialize_app(cred, {'databaseURL': database_url})
            setup_device_and_config()
        except Exception as e:
            print(f"설정 실패: {e}"); return

    # 2. 스레드 시작
    t1 = threading.Thread(target=firebase_thread_worker)
    t2 = threading.Thread(target=arduino_thread_worker)
    t1.start()
    t2.start()
    
    try:
        while main_loop_running: time.sleep(1)
    except KeyboardInterrupt:
        main_loop_running = False
        t1.join(timeout=2)
        t2.join(timeout=2)

if __name__ == '__main__':
    main()
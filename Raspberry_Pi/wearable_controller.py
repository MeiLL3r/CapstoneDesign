#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import firebase_admin
from firebase_admin import credentials, db
import serial
import time
import json
import os
import atexit

# --- 설정 (Constants) ---
CONFIG_FILE = 'config.json'
FIREBASE_KEY_FILE = 'firebase-key.json'
# 아두이노가 연결된 USB 포트와 통신 속도 (환경에 맞게 수정 필요)
ARDUINO_PORT = '/dev/ttyACM0' # /dev/ttyACM0 또는 /dev/ttyUSB0
BAUD_RATE = 9600

# --- 전역 변수 ---
device_id = None
firebase_app = None
arduino = None
# 리스너 객체를 저장할 전역 변수 추가
listener = None

# --- 1. 최초 실행 시 설정 함수 ---
def setup_device():
    global device_id
    print("--- 최초 설정 모드 ---")
    
    device_id = input("기기 고유번호를 입력하세요 (예: 123): ").strip()
    device_name = input(f"'{device_id}' 기기의 별명을 입력하세요 (예: 내 작업복): ").strip()

    if not device_id or not device_name:
        print("오류: 기기 고유번호와 별명은 반드시 입력해야 합니다.")
        exit()

    print("Firebase에 초기 데이터 구조를 생성합니다...")
    try:
        ref = db.reference(f'devices/{device_id}', app=firebase_app)
        ref.set({
            'name': device_name,
            'connection': {'status': 'offline', 'last_seen': 0},
            'control': {'mode': 'cooling', 'target_temp': 22},
            'status': {
                'current_temp': 0,
                'sensors': {
                    'sensor_01': {'name': '왼쪽 팔', 'temp': 0, 'posX': 0.168, 'posY': 0.55},
                    'sensor_02': {'name': '앞', 'temp': 0, 'posX': 0.3, 'posY': 0.5},
                    'sensor_03': {'name': '상체 아래', 'temp': 0, 'posX': 0.7, 'posY': 0.5},
                    'sensor_04': {'name': '오른쪽 팔', 'temp': 0, 'posX': 0.435, 'posY': 0.55}
                }
            }
        })
        print("✅ Firebase 데이터 셋업 완료.")
    except Exception as e:
        print(f"❌ Firebase 셋업 실패: {e}")
        exit()

    with open(CONFIG_FILE, 'w') as f:
        json.dump({'device_id': device_id}, f)
    print(f"✅ 설정 파일 '{CONFIG_FILE}'이 생성되었습니다.")

# --- 2. Firebase 리스너 콜백 함수 ---
def control_listener(event):
    print(f"🔥 Firebase 제어 데이터 변경 감지: 경로({event.path}), 데이터({event.data})")
    
    if event.path == '/mode':
        mode = event.data
        if arduino and arduino.is_open:
            command = f"MODE:{mode.upper()}\n"
            arduino.write(command.encode())
            print(f"-> 아두이노 전송: {command.strip()}")
            
    elif event.path == '/target_temp':
        temp = event.data
        if arduino and arduino.is_open:
            command = f"TEMP:{temp}\n"
            arduino.write(command.encode())
            print(f"-> 아두이노 전송: {command.strip()}")

# --- 3. 프로그램 종료 시 호출될 함수 ---
def set_offline():
    if device_id and firebase_app:
        print("\n--- 프로그램 종료. 연결 상태를 'offline'으로 변경합니다. ---")
        try:
            connection_ref = db.reference(f'devices/{device_id}/connection', app=firebase_app)
            connection_ref.update({
                'status': 'offline',
                'last_seen': {'.sv': 'timestamp'}
            })
            print("✅ 연결 상태 업데이트 완료.")
        except Exception as e:
            print(f"❌ 종료 시 연결 상태 업데이트 실패: {e}")

# --- 4. 메인 로직 ---
def main():
    global device_id, firebase_app, arduino, listener # 전역 변수 listener 추가
    
    atexit.register(set_offline)

    script_dir = os.path.dirname(os.path.abspath(__file__))

    # --- 4-1. Firebase 초기화 ---
    try:
        key_path = os.path.join(script_dir, FIREBASE_KEY_FILE)
        cred = credentials.Certificate(key_path)
        project_id = cred.project_id
        if not project_id:
            raise ValueError("서비스 계정 키 파일에서 project_id를 찾을 수 없습니다.")
        database_url = f'https://{project_id}-default-rtdb.firebaseio.com/'
        firebase_app = firebase_admin.initialize_app(cred, {'databaseURL': database_url})
        print("✅ Firebase 초기화 성공.")
    except Exception as e:
        print(f"❌ Firebase 초기화 실패: {e}")
        exit()

    # --- 4-2. 설정 파일 확인 및 최초 설정 실행 ---
    config_path = os.path.join(script_dir, CONFIG_FILE)
    if not os.path.exists(config_path):
        setup_device()
    else:
        with open(config_path, 'r') as f:
            config = json.load(f)
            device_id = config.get('device_id')
        if not device_id:
            print("오류: 설정 파일이 손상되었습니다. config.json 파일을 삭제하고 다시 실행하세요.")
            exit()
        print(f"--- 일반 실행 모드 (기기 ID: {device_id}) ---")

    # --- 4-3. Firebase 연결 상태 'online'으로 설정 ---
    connection_ref = db.reference(f'devices/{device_id}/connection', app=firebase_app)
    connection_ref.update({
        'status': 'online',
        'last_seen': {'.sv': 'timestamp'}
    })
    print("✅ Firebase 연결 상태 'online'으로 설정 완료.")

    # --- 4-4. Firebase 제어 데이터 리스너 시작 ---
    control_ref = db.reference(f'devices/{device_id}/control', app=firebase_app)
    # 리스너를 시작하고 반환된 객체를 listener 변수에 저장
    listener = control_ref.listen(control_listener)
    print("📡 Firebase 제어 데이터 감시 시작...")

    # --- 4-5. 아두이노 시리얼 연결 시도 ---
    try:
        arduino = serial.Serial(ARDUINO_PORT, BAUD_RATE, timeout=1)
        time.sleep(2)
        print(f"✅ 아두이노 연결 성공 ({ARDUINO_PORT})")
    except serial.SerialException as e:
        print(f"⚠️ 아두이노 연결 실패: {e}. 데이터 수신만 가능합니다.")
        arduino = None

    # --- 4-6. 메인 루프를 try...finally로 감싸서 항상 리소스를 해제하도록 변경 ---
    try:
        print("🔄 아두이노 데이터 수신 대기 시작...")
        status_ref = db.reference(f'devices/{device_id}/status', app=firebase_app)
        connection_ref = db.reference(f'devices/{device_id}/connection', app=firebase_app)
    
        last_heartbeat_time = time.time()
        
        while True:
            if arduino and arduino.in_waiting > 0:
                try:
                    line = arduino.readline().decode('utf-8').strip()
                    if line.startswith("SENSORS:"):
                        parts = line.split(":")[1].split(",")
                        if len(parts) == 4:
                            temps = [int(p) for p in parts]
                            print(f"<- 아두이노 수신: {temps}")
                            
                            avg_temp = sum(temps) // len(temps)
                            status_ref.update({
                                'current_temp': avg_temp,
                                'sensors/sensor_01/temp': temps[0],
                                'sensors/sensor_02/temp': temps[1],
                                'sensors/sensor_03/temp': temps[2],
                                'sensors/sensor_04/temp': temps[3]
                            })
                except Exception as e:
                    print(f"아두이노 데이터 처리 중 오류: {e}")
            
            # 60초마다 last_seen 업데이트
            current_time = time.time()
            if current_time - last_heartbeat_time > 10:
                print("❤️ 하트비트 전송: last_seen 업데이트...")
                connection_ref.child('last_seen').set({'.sv': 'timestamp'})
                last_heartbeat_time = current_time

            time.sleep(1)
    except KeyboardInterrupt:
        print("\n프로그램을 종료합니다.")
    finally:
        # --- 여기가 핵심! ---
        # 프로그램 종료 직전, 리스너 스레드를 명시적으로 종료합니다.
        if listener:
            print("Firebase 리스너를 종료합니다...")
            listener.close()
        # 아두이노 연결도 닫아주는 것이 좋습니다.
        if arduino and arduino.is_open:
            print("아두이노 연결을 닫습니다...")
            arduino.close()

# --- 프로그램 시작점 ---
if __name__ == '__main__':
    main()
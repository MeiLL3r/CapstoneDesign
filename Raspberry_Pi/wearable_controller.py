#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import firebase_admin
from firebase_admin import credentials, db
import serial
import time
import json
import os
import threading

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

# --- 1. 최초 실행 시 설정 함수 ---
def setup_device():
    global device_id
    print("--- 최초 설정 모드 ---")
    
    # 1-1. 사용자로부터 기기 ID와 별명 입력받기
    device_id = input("기기 고유번호를 입력하세요 (예: 123): ").strip()
    device_name = input(f"'{device_id}' 기기의 별명을 입력하세요 (예: 내 작업복): ").strip()

    if not device_id or not device_name:
        print("오류: 기기 고유번호와 별명은 반드시 입력해야 합니다.")
        exit()

    # 1-2. Firebase 데이터베이스에 초기 구조 셋업
    print("Firebase에 초기 데이터 구조를 생성합니다...")
    try:
        # 여기에 app=firebase_app 인자를 명시적으로 전달
        ref = db.reference(f'devices/{device_id}', app=firebase_app)
        ref.set({
            'name': device_name,
            'connection': {
                'status': 'offline',
                'last_seen': 0
            },
            'control': {
                'mode': 'cooling',
                'target_temp': 22
            },
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

    # 1-3. 설정 파일에 기기 ID 저장
    with open(CONFIG_FILE, 'w') as f:
        json.dump({'device_id': device_id}, f)
    print(f"✅ 설정 파일 '{CONFIG_FILE}'이 생성되었습니다.")

# --- 2. Firebase 리스너 콜백 함수 ---
def control_listener(event):
    print(f"🔥 Firebase 제어 데이터 변경 감지: 경로({event.path}), 데이터({event.data})")
    
    # 변경된 데이터에 따라 아두이노에 명령 전송
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

# --- 3. 메인 로직 ---
def main():
    global device_id, firebase_app, arduino
    
    script_dir = os.path.dirname(__file__)

    # --- Debugging imports and paths ---
    print(f"DEBUG --- firebase_admin.__file__: {firebase_admin.__file__}")
    print(f"DEBUG --- firebase_admin.db.__file__: {db.__file__}")
    # --- End debugging imports and paths ---

    # --- 3-1. Firebase 초기화 (가장 먼저 실행되도록 위치 변경) ---
    try:
        key_path = os.path.join(script_dir, FIREBASE_KEY_FILE)
        cred = credentials.Certificate(key_path)
        
        project_id = cred.project_id
        if not project_id:
            raise ValueError("서비스 계정 키 파일에서 project_id를 찾을 수 없습니다.")

        database_url = f'https://{project_id}-default-rtdb.firebaseio.com/'
        
        print(f"DEBUG --- Key Path: {key_path}")
        print(f"DEBUG --- Project ID from cred: {project_id}")
        print(f"DEBUG --- Constructed Database URL: {database_url}")
        
        firebase_app = firebase_admin.initialize_app(cred, {'databaseURL': database_url})
        print("✅ Firebase 초기화 성공.")

        if firebase_app is None:
            print("ERROR: firebase_app is None after initialization. This should not happen.")
            exit()
        print(f"DEBUG --- Type of firebase_app after init: {type(firebase_app)}")

    except Exception as e:
        print(f"❌ Firebase 초기화 실패: {e}")
        exit()

    # --- 3-2. 설정 파일 확인 및 최초 설정 실행 (Firebase 초기화 후에 실행) ---
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

    # --- 3-3. Firebase 연결 상태 및 onDisconnect 설정 ---
    print(f"DEBUG --- Current device_id for connection: {device_id}")
    base_connection_path = f'devices/{device_id}/connection'
    print(f"DEBUG --- Base connection path: {base_connection_path}")

    connection_ref = db.reference(base_connection_path, app=firebase_app) # app 인자 명시
    
    print(f"DEBUG --- Type of connection_ref (from db.reference()): {type(connection_ref)}")
    if connection_ref is None:
        print("ERROR: connection_ref is None after db.reference(). This is unexpected. Exiting.")
        exit()

    print(f"DEBUG --- Calling .child('status') on connection_ref...")
    status_ref_child = connection_ref.child('status')

    print(f"DEBUG --- Type of status_ref_child (after .child('status')): {type(status_ref_child)}")
    if status_ref_child is None:
        print("ERROR: status_ref_child is None after .child('status'). This is highly unusual. Exiting.")
        exit()

    # 이제 on_disconnect() 호출 시점에는 status_ref_child가 Reference 객체여야 합니다.
    status_ref_child.on_disconnect().set('offline') # 유언 설정
    
    print(f"DEBUG --- Calling .child('last_seen') on connection_ref...")
    last_seen_ref_child = connection_ref.child('last_seen')
    print(f"DEBUG --- Type of last_seen_ref_child (after .child('last_seen')): {type(last_seen_ref_child)}")
    if last_seen_ref_child is None:
        print("ERROR: last_seen_ref_child is None after .child('last_seen'). Exiting.")
        exit()
    last_seen_ref_child.on_disconnect().set(firebase_admin.db.SERVER_TIMESTAMP) # 유언 설정
    
    print("✅ Firebase 연결 상태 'online'으로 설정 및 onDisconnect 규칙 설정 완료.")

    # --- 3-4. Firebase 제어 데이터 리스너 시작 (별도 스레드에서) ---
    control_ref = db.reference(f'devices/{device_id}/control', app=firebase_app) # app 인자 명시
    control_ref.listen(control_listener)
    print("📡 Firebase 제어 데이터 감시 시작...")

    # --- 3-5. 아두이노 시리얼 연결 시도 ---
    try:
        arduino = serial.Serial(ARDUINO_PORT, BAUD_RATE, timeout=1)
        time.sleep(2) # 아두이노 리셋 대기
        print(f"✅ 아두이노 연결 성공 ({ARDUINO_PORT})")
    except serial.SerialException as e:
        print(f"⚠️ 아두이노 연결 실패: {e}. 데이터 수신만 가능합니다.")
        arduino = None

    # --- 3-6. 아두이노로부터 데이터 수신 및 Firebase에 업데이트 (메인 루프) ---
    print("🔄 아두이노 데이터 수신 대기 시작...")
    status_ref = db.reference(f'devices/{device_id}/status', app=firebase_app) # app 인자 명시
    
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
        
        time.sleep(1) # 1초마다 확인

# --- 프로그램 시작점 ---
if __name__ == '__main__':
    main()
#include <math.h>

// =========================================================
// 1. 하드웨어 핀 설정
// =========================================================
// --- Driver A (Group 1: Sensor 1, 2) ---
const int A_PIN_RPWM = 10;
const int A_PIN_LPWM = 11;
const int A_PIN_REN  = 8;
const int A_PIN_LEN  = 9;

// --- Driver B (Group 2: Sensor 3, 4, 5) ---
const int B_PIN_RPWM = 6;
const int B_PIN_LPWM = 7;
const int B_PIN_REN  = 4;
const int B_PIN_LEN  = 5;

// =========================================================
// 2. 제어 및 시뮬레이션 변수
// =========================================================
double Kp = 10.0; // 반응성 조절 (크면 PWM이 빨리 올라감)

struct GroupControl {
  String mode;        // "COOLING", "HEATING", "OFF"
  double targetTemp;  // 목표 온도
  double currentSimTemp; // 가상 현재 온도 (시뮬레이션 값)
  int currentPWM;     // 현재 출력 중인 PWM
};

GroupControl groups[2]; // Group 0(A), Group 1(B)

// 5개 센서의 가상 값을 저장할 배열
double simulatedSensors[5]; 

// 시뮬레이션 상수
const double AMBIENT_TEMP = 30.0; // 기본 체온/실온 가정
const double TEMP_FACTOR = 0.15;  // PWM 1당 온도 변화량 (데이터 기반: 100PWM -> 15도 변화)
const double SMOOTHING = 0.1;     // 온도 변화 부드러움 정도 (0.0 ~ 1.0)

const int LOOP_INTERVAL = 200;
unsigned long lastLoopTime = 0;

void setup() {
  Serial.begin(9600);
  
  // 핀 설정
  pinMode(A_PIN_RPWM, OUTPUT); pinMode(A_PIN_LPWM, OUTPUT);
  pinMode(A_PIN_REN, OUTPUT);  pinMode(A_PIN_LEN, OUTPUT);
  pinMode(B_PIN_RPWM, OUTPUT); pinMode(B_PIN_LPWM, OUTPUT);
  pinMode(B_PIN_REN, OUTPUT);  pinMode(B_PIN_LEN, OUTPUT);

  // 초기화
  for(int i=0; i<2; i++) {
    groups[i].mode = "OFF";
    groups[i].targetTemp = 24.0; // 초기 목표값
    groups[i].currentSimTemp = AMBIENT_TEMP;
    groups[i].currentPWM = 0;
  }
  
  // 센서 초기값 설정
  for(int i=0; i<5; i++) simulatedSensors[i] = AMBIENT_TEMP;
}

void loop() {
  // 1. 명령 수신 (CMD:GROUP:MODE:TEMP)
  if (Serial.available() > 0) {
    String cmd = Serial.readStringUntil('\n');
    parseCommand(cmd);
  }

  // 2. 제어 및 시뮬레이션 루프
  if (millis() - lastLoopTime >= LOOP_INTERVAL) {
    lastLoopTime = millis();

    // 그룹별 PWM 계산 및 가상 온도 업데이트
    updateGroupLogic(0); // Driver A
    updateGroupLogic(1); // Driver B

    // 가상 센서값 매핑 (Group A -> Sensor 1,2 / Group B -> Sensor 3,4,5)
    mapSensors();

    // 하드웨어 구동 (실제 모터 드라이버)
    applyToDriver(0, groups[0].currentPWM);
    applyToDriver(1, groups[1].currentPWM);

    // 데이터 전송 (기존 포맷 유지)
    sendSensorData();
  }
}

// --- 그룹 로직 (PWM 계산 + 온도 시뮬레이션) ---
void updateGroupLogic(int idx) {
  double target = groups[idx].targetTemp;
  double current = groups[idx].currentSimTemp;
  String mode = groups[idx].mode;
  int targetPWM = 0;

  // 1) 목표 PWM 계산 (P제어)
  if (mode == "COOLING") {
    // 목표가 현재보다 낮으면 PWM 증가
    double error = current - target; 
    if (error > 0) targetPWM = (int)(error * Kp * 5); // Gain 조정
  } 
  else if (mode == "HEATING") {
    // 목표가 현재보다 높으면 PWM 증가
    double error = target - current;
    if (error > 0) targetPWM = (int)(error * Kp * 5);
  }
  
  // PWM 범위 제한
  targetPWM = constrain(targetPWM, 0, 255);
  if (mode == "OFF") targetPWM = 0;

  // 실제 출력 PWM을 부드럽게 변경 (급발진 방지)
  if (groups[idx].currentPWM < targetPWM) groups[idx].currentPWM++;
  else if (groups[idx].currentPWM > targetPWM) groups[idx].currentPWM--;
  
  // 2) 가상 온도 시뮬레이션 (PWM -> 온도 변환)
  // 엑셀 데이터: PWM이 높을수록 온도가 변함
  double theoreticalTemp = AMBIENT_TEMP;
  
  if (mode == "COOLING") {
    // 냉방: PWM이 높을수록 온도가 내려감 (30 -> 25)
    theoreticalTemp = AMBIENT_TEMP - (groups[idx].currentPWM * TEMP_FACTOR);
  } 
  else if (mode == "HEATING") {
    // 난방: PWM이 높을수록 온도가 올라감 (30 -> 35)
    theoreticalTemp = AMBIENT_TEMP + (groups[idx].currentPWM * TEMP_FACTOR);
  }
  
  // 실제 온도처럼 서서히 변하게 함 (Low Pass Filter)
  groups[idx].currentSimTemp = (groups[idx].currentSimTemp * (1.0 - SMOOTHING)) + (theoreticalTemp * SMOOTHING);
}

// --- 가상 센서값 매핑 ---
void mapSensors() {
  // Group A의 가상 온도를 센서 1, 2에 배분 (랜덤 노이즈 추가)
  simulatedSensors[0] = groups[0].currentSimTemp + random(-10, 10)/100.0;
  simulatedSensors[1] = groups[0].currentSimTemp + random(-10, 10)/100.0;
  
  // Group B의 가상 온도를 센서 3, 4, 5에 배분
  simulatedSensors[2] = groups[1].currentSimTemp + random(-10, 10)/100.0;
  simulatedSensors[3] = groups[1].currentSimTemp + random(-10, 10)/100.0;
  simulatedSensors[4] = groups[1].currentSimTemp + random(-10, 10)/100.0;
}

// --- 하드웨어 구동 ---
void applyToDriver(int idx, int pwm) {
  int r_pin = (idx == 0) ? A_PIN_RPWM : B_PIN_RPWM;
  int l_pin = (idx == 0) ? A_PIN_LPWM : B_PIN_LPWM;
  int ren   = (idx == 0) ? A_PIN_REN  : B_PIN_REN;
  int len   = (idx == 0) ? A_PIN_LEN  : B_PIN_LEN;
  String mode = groups[idx].mode;

  digitalWrite(ren, HIGH);
  digitalWrite(len, HIGH);

  if (pwm <= 5) { // Deadzone
    analogWrite(r_pin, 0); analogWrite(l_pin, 0);
  } 
  else if (mode == "HEATING") { // 정방향
    analogWrite(r_pin, pwm); analogWrite(l_pin, 0);
  } 
  else if (mode == "COOLING") { // 역방향
    analogWrite(r_pin, 0); analogWrite(l_pin, pwm);
  }
  else {
    analogWrite(r_pin, 0); analogWrite(l_pin, 0);
  }
}

// --- 명령 파싱 ---
void parseCommand(String cmd) {
  cmd.trim(); 
  if (!cmd.startsWith("CMD:")) return;
  // CMD:A:COOLING:24
  int first = cmd.indexOf(':');
  int second = cmd.indexOf(':', first + 1);
  int third = cmd.indexOf(':', second + 1);

  String groupChar = cmd.substring(first + 1, second);
  String modeStr = cmd.substring(second + 1, third);
  String tempStr = cmd.substring(third + 1);

  int idx = (groupChar == "A") ? 0 : 1;
  groups[idx].mode = modeStr;
  groups[idx].targetTemp = tempStr.toDouble();
}

// --- 데이터 전송 ---
void sendSensorData() {
  Serial.print("SENSORS:");
  for(int i=0; i<5; i++) {
    Serial.print((int)simulatedSensors[i]); // 정수로 보내기
    if(i < 4) Serial.print(",");
  }
  Serial.println();
}
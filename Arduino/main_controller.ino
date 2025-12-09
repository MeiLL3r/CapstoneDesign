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

// --- 센서 (5개) ---
const int SENSORS[5] = {A0, A1, A2, A3, A4}; // 1~5번 센서

// =========================================================
// 2. 제어 변수
// =========================================================
double Kp = 40.0, Ki = 0.5, Kd = 1.0;

struct GroupControl {
  String mode;        // "COOLING", "HEATING", "OFF"
  double targetTemp;
  double currentAvgTemp; // 그룹 내 센서 평균값
  double errorSum;
  double lastError;
};

GroupControl groups[2]; // Group 0(A), Group 1(B)
double sensorValues[5]; // 개별 센서값 저장용

const int PID_INTERVAL = 200;
unsigned long lastPidTime = 0;

// 서미스터 상수 (사용하는 센서에 맞춰 수정 필수)
const float SERIES_RESISTOR = 10000;
const float NOMINAL_RESISTOR = 10000;
const float NOMINAL_TEMP = 25;
const float B_COEFFICIENT = 3950;

void setup() {
  Serial.begin(9600);
  pinMode(A_PIN_RPWM, OUTPUT); pinMode(A_PIN_LPWM, OUTPUT);
  pinMode(A_PIN_REN, OUTPUT);  pinMode(A_PIN_LEN, OUTPUT);
  pinMode(B_PIN_RPWM, OUTPUT); pinMode(B_PIN_LPWM, OUTPUT);
  pinMode(B_PIN_REN, OUTPUT);  pinMode(B_PIN_LEN, OUTPUT);

  // 초기화
  for(int i=0; i<2; i++) {
    groups[i].mode = "OFF";
    groups[i].targetTemp = 25.0;
    groups[i].errorSum = 0;
  }
}

void loop() {
  // 1. 명령 수신 (CMD:GROUP:MODE:TEMP 형식)
  // 예: CMD:A:COOLING:24
  if (Serial.available() > 0) {
    String cmd = Serial.readStringUntil('\n');
    parseCommand(cmd);
  }

  // 2. PID 제어 루프
  if (millis() - lastPidTime >= PID_INTERVAL) {
    lastPidTime = millis();

    // 모든 센서 읽기
    for(int i=0; i<5; i++) {
      sensorValues[i] = readThermistor(SENSORS[i]);
    }

    // 그룹별 현재 온도 계산 (평균)
    groups[0].currentAvgTemp = (sensorValues[0] + sensorValues[1]) / 2.0;
    groups[1].currentAvgTemp = (sensorValues[2] + sensorValues[3] + sensorValues[4]) / 3.0;

    // PID 계산 및 구동
    applyToDriver(0, calculatePID(0)); // Driver A
    applyToDriver(1, calculatePID(1)); // Driver B

    // 상태 전송 (SENSORS:t1,t2,t3,t4,t5)
    sendSensorData();
  }
}

// --- PID 계산 ---
int calculatePID(int groupIdx) {
  if (groups[groupIdx].mode == "OFF") return 0;

  double error = groups[groupIdx].targetTemp - groups[groupIdx].currentAvgTemp;
  groups[groupIdx].errorSum += error;
  
  // Windup 방지
  if (groups[groupIdx].errorSum > 100) groups[groupIdx].errorSum = 100;
  if (groups[groupIdx].errorSum < -100) groups[groupIdx].errorSum = -100;

  double dError = error - groups[groupIdx].lastError;
  groups[groupIdx].lastError = error;

  double output = (Kp * error) + (Ki * groups[groupIdx].errorSum) + (Kd * dError);
  int pwm = (int)output;

  // 단방향 제어 로직 (모드에 따라 역방향 차단)
  if (groups[groupIdx].mode == "COOLING") {
    if (pwm > 0) pwm = 0; // 가열 방향 차단
  } else if (groups[groupIdx].mode == "HEATING") {
    if (pwm < 0) pwm = 0; // 냉각 방향 차단
  }

  return constrain(pwm, -255, 255);
}

// --- 하드웨어 구동 ---
void applyToDriver(int groupIdx, int pwm) {
  int r_pin = (groupIdx == 0) ? A_PIN_RPWM : B_PIN_RPWM;
  int l_pin = (groupIdx == 0) ? A_PIN_LPWM : B_PIN_LPWM;
  int ren   = (groupIdx == 0) ? A_PIN_REN  : B_PIN_REN;
  int len   = (groupIdx == 0) ? A_PIN_LEN  : B_PIN_LEN;

  digitalWrite(ren, HIGH);
  digitalWrite(len, HIGH);

  if (pwm == 0) {
    analogWrite(r_pin, 0); analogWrite(l_pin, 0);
  } else if (pwm > 0) { // 정방향 (Heating 가정)
    analogWrite(r_pin, pwm); analogWrite(l_pin, 0);
  } else { // 역방향 (Cooling 가정)
    analogWrite(r_pin, 0); analogWrite(l_pin, abs(pwm));
  }
}

// --- 명령 파싱 ---
void parseCommand(String cmd) {
  cmd.trim(); // CMD:A:COOLING:24
  if (!cmd.startsWith("CMD:")) return;
  
  int first = cmd.indexOf(':');
  int second = cmd.indexOf(':', first + 1);
  int third = cmd.indexOf(':', second + 1);

  String groupChar = cmd.substring(first + 1, second); // A or B
  String modeStr = cmd.substring(second + 1, third);   // COOLING
  String tempStr = cmd.substring(third + 1);           // 24

  int idx = (groupChar == "A") ? 0 : 1;
  
  groups[idx].mode = modeStr;
  groups[idx].targetTemp = tempStr.toDouble();
  groups[idx].errorSum = 0; // 모드/온도 변경시 적분 초기화
}

// --- 센서 읽기 ---
double readThermistor(int pin) {
  int raw = analogRead(pin);
  if (raw == 0) return 0;
  float resistance = SERIES_RESISTOR / (1023.0 / raw - 1);
  float steinhart = resistance / NOMINAL_RESISTOR;
  steinhart = log(steinhart);
  steinhart /= B_COEFFICIENT;
  steinhart += 1.0 / (NOMINAL_TEMP + 273.15);
  steinhart = 1.0 / steinhart;
  steinhart -= 273.15;
  return steinhart;
}

// --- 데이터 전송 ---
void sendSensorData() {
  Serial.print("SENSORS:");
  for(int i=0; i<5; i++) {
    Serial.print((int)sensorValues[i]);
    if(i < 4) Serial.print(",");
  }
  Serial.println();
}
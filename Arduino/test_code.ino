/*
 * [사용법 - 시리얼 모니터]
 * '1': PWM 값 10 감소 (약해짐)
 * '2': PWM 값 10 증가 (강해짐)
 * '3': 작동 모드 변경 (정방향/역방향)
*/

// --- 핀 설정 ---
const int PIN_ENA = 10; // PWM 출력 핀 (0~255)
const int PIN_IN1 = 8;  // 방향 제어 핀 1
const int PIN_IN2 = 9;  // 방향 제어 핀 2

// --- 설정 상수 ---
const int PWM_STEP = 10; // 한 번 누를 때마다 변할 PWM 수치

// --- 상태 변수 ---
int currentPwm = 0;      // 현재 PWM 값 (0 ~ 255)
bool isNormalMode = true; // true: 정방향, false: 역방향

void setup() {
  pinMode(PIN_ENA, OUTPUT);
  pinMode(PIN_IN1, OUTPUT);
  pinMode(PIN_IN2, OUTPUT);

  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, LOW);
  analogWrite(PIN_ENA, 0);

  Serial.begin(9600);
  Serial.println("키보드 입력: 1(감소), 2(증가), 3(방향 전환)");
  printStatus();
}

void loop() {
  // 모니터로부터 입력이 들어오면
  if (Serial.available() > 0) {
    char input = Serial.read();

    if (input == '\n' || input == '\r' || input == ' ') return;

    // 입력값에 따른 동작 수행
    switch (input) {
      case '1': // 감소
        currentPwm -= PWM_STEP;
        if (currentPwm < 0) currentPwm = 0;
        break;

      case '2': // 증가
        currentPwm += PWM_STEP;
        if (currentPwm > 255) currentPwm = 255;
        break;

      case '3': // 방향 전환
        isNormalMode = !isNormalMode;
        break;
        
      default:
        // 1, 2, 3 이외의 키
        return; 
    }

    // 하드웨어에 변경된 값 적용
    applyToDriver();
    
    // 화면에 현재 상태 출력
    printStatus();
  }
}

// 변경된 변수 값들을 실제 드라이버에 보내는 함수
void applyToDriver() {
  // 방향 제어
  if (isNormalMode) {
    // 정방향
    digitalWrite(PIN_IN1, HIGH);
    digitalWrite(PIN_IN2, LOW);
  } else {
    // 역방향
    digitalWrite(PIN_IN1, LOW);
    digitalWrite(PIN_IN2, HIGH);
  }

  // 세기(PWM) 제어
  analogWrite(PIN_ENA, currentPwm);
}

// 모니터에 출력하는 함수
void printStatus() {
  Serial.print("현재 상태 -> [ 모드: ");
  
  if (isNormalMode) {
    Serial.print("정방향(Normal)");
  } else {
    Serial.print("역방향(Reverse)");
  }
  
  Serial.print(" ]  |  [ PWM 값: ");
  Serial.print(currentPwm);
  Serial.println(" / 255 ]");
}
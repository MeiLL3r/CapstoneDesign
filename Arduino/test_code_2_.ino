/*
 * [사용법 - 시리얼 모니터]
 *  펠티어 A: 1(감소), 2(증가), 3(방향 전환)
 *  펠티어 B: 4(감소), 5(증가), 6(방향 전환)
 *
 * [하드웨어 기준 - BTS7960 모터 드라이버 2개]
 *
 * --- 펠티어 A ---
 * D10 -> RPWM
 * D11 -> LPWM
 * D8  -> R_EN
 * D9  -> L_EN
 *
 * --- 펠티어 B ---
 * D6  -> RPWM
 * D7  -> LPWM
 * D4  -> R_EN
 * D5  -> L_EN
 *
 * GND는 전부 공통으로 묶기 (Arduino + 전원 + BTS 두 개)
 */

//////////////////////////////////////
// 펠티어 A 핀 구성
//////////////////////////////////////
const int A_PIN_RPWM = 10;
const int A_PIN_LPWM = 11;
const int A_PIN_REN  = 8;
const int A_PIN_LEN  = 9;

//////////////////////////////////////
// 펠티어 B 핀 구성
//////////////////////////////////////
const int B_PIN_RPWM = 6;
const int B_PIN_LPWM = 7;
const int B_PIN_REN  = 4;
const int B_PIN_LEN  = 5;

//////////////////////////////////////
// 공통 설정
//////////////////////////////////////
const int PWM_STEP = 2;

int pwmA = 0;           // 펠티어 A PWM
bool dirA = true;       // A 정/역 모드

int pwmB = 0;           // 펠티어 B PWM
bool dirB = true;       // B 정/역 모드


//////////////////////////////////////////////////////
// SETUP
//////////////////////////////////////////////////////
void setup() {
  // A 핀 설정
  pinMode(A_PIN_RPWM, OUTPUT);
  pinMode(A_PIN_LPWM, OUTPUT);
  pinMode(A_PIN_REN, OUTPUT);
  pinMode(A_PIN_LEN, OUTPUT);

  // B 핀 설정
  pinMode(B_PIN_RPWM, OUTPUT);
  pinMode(B_PIN_LPWM, OUTPUT);
  pinMode(B_PIN_REN, OUTPUT);
  pinMode(B_PIN_LEN, OUTPUT);

  // A 초기 정지
  digitalWrite(A_PIN_REN, LOW);
  digitalWrite(A_PIN_LEN, LOW);
  analogWrite(A_PIN_RPWM, 0);
  analogWrite(A_PIN_LPWM, 0);

  // B 초기 정지
  digitalWrite(B_PIN_REN, LOW);
  digitalWrite(B_PIN_LEN, LOW);
  analogWrite(B_PIN_RPWM, 0);
  analogWrite(B_PIN_LPWM, 0);

  Serial.begin(9600);
  Serial.println("=== 펠티어 A+B 제어 준비 완료 (BTS7960 x 2) ===");
  Serial.println("[A] 1(감소), 2(증가), 3(방향)");
  Serial.println("[B] 4(감소), 5(증가), 6(방향)");

  printStatusA();
  printStatusB();
}


//////////////////////////////////////////////////////
// LOOP
//////////////////////////////////////////////////////
void loop() {
  if (Serial.available() > 0) {
    char input = Serial.read();

    if (input == '\n' || input == '\r' || input == ' ') return;

    switch (input) {
      // ---------- 펠티어 A ----------
      case '1': // A 감소
        pwmA -= PWM_STEP;
        if (pwmA < 0) pwmA = 0;
        break;

      case '2': // A 증가
        pwmA += PWM_STEP;
        if (pwmA > 255) pwmA = 255;
        break;

      case '3': // A 방향 전환
        dirA = !dirA;
        break;

      // ---------- 펠티어 B ----------
      case '4': // B 감소
        pwmB -= PWM_STEP;
        if (pwmB < 0) pwmB = 0;
        break;

      case '5': // B 증가
        pwmB += PWM_STEP;
        if (pwmB > 255) pwmB = 255;
        break;

      case '6': // B 방향 전환
        dirB = !dirB;
        break;

      default:
        return;
    }

    // 변경된 내용 적용
    applyA();
    applyB();

    // 출력
    printStatusA();
    printStatusB();
  }
}


//////////////////////////////////////////////////////
// 펠티어 A 제어 함수 (원래 흐름 그대로 복붙)
//////////////////////////////////////////////////////
void applyA() {
  if (pwmA <= 0) {
    digitalWrite(A_PIN_REN, LOW);
    digitalWrite(A_PIN_LEN, LOW);
    analogWrite(A_PIN_RPWM, 0);
    analogWrite(A_PIN_LPWM, 0);
    return;
  }

  digitalWrite(A_PIN_REN, HIGH);
  digitalWrite(A_PIN_LEN, HIGH);

  if (dirA) {
    analogWrite(A_PIN_RPWM, pwmA);
    analogWrite(A_PIN_LPWM, 0);
  } else {
    analogWrite(A_PIN_RPWM, 0);
    analogWrite(A_PIN_LPWM, pwmA);
  }
}

//////////////////////////////////////////////////////
// 펠티어 B 제어 함수 (A의 복사본 그대로 유지)
//////////////////////////////////////////////////////
void applyB() {
  if (pwmB <= 0) {
    digitalWrite(B_PIN_REN, LOW);
    digitalWrite(B_PIN_LEN, LOW);
    analogWrite(B_PIN_RPWM, 0);
    analogWrite(B_PIN_LPWM, 0);
    return;
  }

  digitalWrite(B_PIN_REN, HIGH);
  digitalWrite(B_PIN_LEN, HIGH);

  if (dirB) {
    analogWrite(B_PIN_RPWM, pwmB);
    analogWrite(B_PIN_LPWM, 0);
  } else {
    analogWrite(B_PIN_RPWM, 0);
    analogWrite(B_PIN_LPWM, pwmB);
  }
}


//////////////////////////////////////////////////////
// 출력 함수 A
//////////////////////////////////////////////////////
void printStatusA() {
  Serial.print("[펠티어 A] 모드: ");
  Serial.print(dirA ? "정방향" : "역방향");
  Serial.print(" | PWM: ");
  Serial.print(pwmA);
  Serial.print("/255 (");
  Serial.print(pwmA / 255.0 * 100.0, 1);
  Serial.println("%)");
}

//////////////////////////////////////////////////////
// 출력 함수 B
//////////////////////////////////////////////////////
void printStatusB() {
  Serial.print("[펠티어 B] 모드: ");
  Serial.print(dirB ? "정방향" : "역방향");
  Serial.print(" | PWM: ");
  Serial.print(pwmB);
  Serial.print("/255 (");
  Serial.print(pwmB / 255.0 * 100.0, 1);
  Serial.println("%)");
}
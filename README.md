# DVP 증권결제 시뮬레이터

증권 이전과 대금 지급이 반드시 함께 성공하거나 함께 실패하는 DVP(Delivery versus Payment) 결제를,
넷팅(netting)과 동시성 제어(concurrency control)까지 포함해 시뮬레이션하는 Spring Boot 프로젝트입니다.

## 핵심 아이디어

### 1. DVP 원자성 = 하나의 DB 트랜잭션
`SettlementService.settleInstruction()`은 증권 이전과 대금 지급을 **하나의 `@Transactional` 메서드** 안에서
처리합니다. 먼저 양쪽 잔고를 잠근 뒤, 두 다리(leg)가 모두 가능한지 미리 검증하고, 검증을 통과했을 때만
두 다리를 함께 반영합니다. 검증에 실패하면 어떤 잔고도 건드리지 않은 채 실패 사유만 기록합니다 —
"커밋되면 둘 다 반영, 아니면 둘 다 무효"라는 KSD의 결제 커밋 비유를 그대로 코드로 옮긴 것입니다.

### 2. 넷팅 = N건의 거래를 참가자당 1건의 순포지션으로
같은 결제일의 거래들을 참가자×종목 단위로 상계해 순매수/순매도 포지션을 계산합니다(`NettingService`).
실제 이동은 임의의 두 참가자를 매칭하는 대신, 예약된 **CCP(중앙청산소) 참가자**를 매개로 이루어집니다 —
참가자마다 "CCP에게 인도" 또는 "CCP로부터 수령" 지시 하나씩만 생기므로 N:N 매칭 문제를 피할 수 있습니다.

CCP 자신은 배치 도중 일시적으로 잔고가 음수가 될 수 있도록 예외 처리되어 있습니다(`debitUnchecked`).
모든 거래의 순포지션 합은 항상 0이기 때문에(모든 거래는 매수자 +수량, 매도자 -수량을 만드니까),
배치가 끝까지 성공하면 CCP의 잔고는 수학적으로 정확히 0으로 돌아옵니다 — 실제 CCP가 결제이행기금으로
커버하는 타이밍 리스크를 단순화해서 흉내낸 것입니다.

### 3. 동시성 제어 = 고정된 순서의 비관적 잠금
여러 결제가 동시에 같은 계좌를 건드릴 때 초과 인출(overdraft)이나 lost update가 생기지 않도록,
모든 계좌 잠금은 **id 오름차순**으로 획득합니다(현금 계좌 먼저, 그다음 증권 보유). 모든 트랜잭션이
같은 순서로 잠그기 때문에 서로 다른 트랜잭션 사이에 순환 대기(교착상태)가 생길 수 없습니다.

`SettlementConcurrencyTest`는 이걸 직접 증명합니다: 잔고 1000짜리 증권을 20명이 60개씩 동시에 사려고
시도하면(총 1200 > 1000), 정확히 16건만 성공하고 4건은 실패하며, 성공/실패 여부와 무관하게 전체 증권·
현금 총량은 정확히 보존됩니다. 잠금이 없다면 두 스레드가 같은 잔고를 동시에 읽어 둘 다 통과시키는
lost update가 발생해 잔고가 음수로 내려갈 수 있습니다.

## 실행

```bash
docker compose up -d
./gradlew bootRun
```

기본 포트는 `8080`입니다.

## 테스트

```bash
./gradlew test
```

`NetAccumulatorTest`/`NettingReductionDemoTest`는 순수 넷팅 계산 로직에 대한 단위 테스트(DB 불필요)이고,
나머지는 Testcontainers로 실제 PostgreSQL 컨테이너를 띄워 검증하는 통합 테스트입니다
(로컬에 Docker가 실행 중이어야 합니다):

**정상/실패 시나리오**
- `SettlementAtomicityTest` — 성공 시 두 다리가 함께 반영되는지, 증권 부족으로 실패 시 아무것도 반영되지 않는지
- `FailureScenarioTest`
  - 현금 부족으로 인한 실패 시에도 아무것도 반영되지 않는지
  - 이미 SETTLED인 지시를 다시 실행해도 중복 반영되지 않는지(멱등성)
  - 존재하지 않는 지시 id를 실행하면 예외가 나는지
  - **배치 부분 실패**: 한 참가자(BX)는 현금이 부족해 실패하고 다른 참가자(BY)는 정상 정산되는 배치를 만들어서,
    (a) BX의 실패가 BY의 정산을 막지 않는지, (b) 이 경우 CCP가 정확히 0으로 복귀하지 못하고 잔여 불균형이
    남는다는 것 — 즉 넷팅 이후에는 원거래의 두 다리가 서로 다른 트랜잭션으로 쪼개지기 때문에, 한쪽만
    실패하면 CCP가 그 차액을 떠안는다는 이 설계의 실제 한계를 재현
- `NettingBatchTest` — 3자간 거래가 넷팅을 거쳐 CCP를 통해 정확히 정산되고, CCP가 잔고 0으로 복귀하는지

**동시성 시나리오**
- `SettlementConcurrencyTest` — 동시 결제 스트레스 테스트 (초과 인출 방지, lost update 방지): 잔고 1000짜리
  증권을 20명이 60개씩 동시에 사려고 시도하면(총 1200 > 1000), 정확히 16건만 성공하고 4건은 실패하며,
  전체 증권·현금 총량은 정확히 보존됨을 검증
- `ConcurrentInstructionExecutionTest` — 같은 결제 지시를 10개 스레드가 동시에 실행해도 딱 한 번만
  반영되는지(중복 실행 방지)
- `ConcurrentBatchClaimTest` — 같은 결제일에 두 배치 실행이 동시에 들어와도 거래가 중복 정산되거나
  누락되지 않고 정확히 한쪽에만 귀속되는지

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/participants` | 참가자 등록 |
| POST | `/instruments` | 종목 등록 |
| POST | `/accounts/deposit-cash` | 현금 초기 입금 (DVP 아님, 자본 주입) |
| POST | `/accounts/deposit-securities` | 증권 초기 입고 (DVP 아님, 자본 주입) |
| GET | `/accounts/{participantId}` | 현금·증권 잔고 조회 |
| GET | `/accounts/{participantId}/ledger` | 원장(감사 추적) 조회 |
| POST | `/trades` | 거래 등록 |
| POST | `/trades/{id}/settle` | 넷팅 없이 즉시 단건 DVP 결제 |
| POST | `/settlement-batches/{date}/run` | 해당일 넷팅 계산 + 배치 결제 실행 |
| GET | `/settlement-batches/{id}` | 배치 결과(순포지션, 결제 지시, 실패 사유) 조회 |

## 알려진 단순화 / 한계

- 넷팅 시 참가자의 순수량이 정확히 0이 되는 경우, 가격 차이로 인한 잔여 현금 손익(residual)은
  이 시뮬레이터의 범위 밖으로 두고 무시합니다.
- 통화는 KRW 단일 통화만 지원합니다.
- **넷팅 배치 부분 실패 시 CCP 잔여 불균형**: 넷팅은 원거래를 두 개의 독립 트랜잭션(참가자→CCP,
  CCP→참가자)으로 쪼갭니다. 한쪽만 실패하면 CCP가 그 차액(증권 또는 현금)을 영구히 떠안게 됩니다 —
  완전히 성공한 배치라면 수학적으로 항상 0으로 돌아오지만, 부분 실패 배치는 그렇지 않습니다.
  `FailureScenarioTest#partialFailureInABatchSettlesUnaffectedParticipantsButLeavesCcpResidual`가
  이 상황을 그대로 재현합니다. 실제 CSD/CCP는 결제이행기금(guarantee fund)이나 예약(reservation)/
  2단계 커밋 프로토콜로 이 갭을 메우는데, 이 시뮬레이터는 그 부분까지는 구현하지 않았습니다.

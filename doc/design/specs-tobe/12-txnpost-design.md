# サブシステム設計書（TO-BE） — 12-txnpost

> COBOL `TXPOST-RUN-BATCH` / `TXPOST-REVERSE` を Java の **`PostStage`** へ移行する TO-BE 設計。
> 複式記帳の中核。**PostgreSQL SERIALIZABLE + リトライ + トランザクショナルアウトボックス**を厳密に保存する。
> AS-IS 一次情報: [specs-asis/02-transaction-pipeline.md § 12](../specs-asis/02-transaction-pipeline.md) / [src/txpost-run-batch.sqb](../../../subsystems/12-txnpost/src/txpost-run-batch.sqb)
> 横断方針: [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md)

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `12-txnpost` |
| ディレクトリ | [subsystems/12-txnpost/](../../../subsystems/12-txnpost/) |
| 分類 | トランザクション処理系（記帳・最終確定） |
| API契約 | [copy/api/tx-post-api.cpy](../../../subsystems/12-txnpost/copy/api/tx-post-api.cpy) |
| 記帳先 | PostgreSQL 15（`transactions`/`postings`/`balances`/`audit_outbox`） |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

11-txnsortmerge が確定した ready 取引を **複式簿記（借方=貸方）として PostgreSQL に記帳**する。冪等性（再投入の二重記帳防止）、残高不足チェック、禁止操作チェックを行い、原子的に `transactions`/`postings`/`balances` を更新する。同一トランザクション内で `audit_outbox` に監査イベントを書き込む（トランザクショナルアウトボックス）。逆仕訳（reversal）にも対応する。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | `11-txnsortmerge`（`List<ReadyTxn>`） | 記帳対象を受け取る |
| 下流 | `21-audit`（`audit_outbox` 経由）、`20-integrationout` | 監査ドレイン・対外連携 |
| 記帳先 | PostgreSQL（[db/migration/V1__initial_schema.sql](../../../db/migration/V1__initial_schema.sql)、[V7__audit_outbox.sql](../../../db/migration/V7__audit_outbox.sql)） | 取引/仕訳/残高/アウトボックス |
| 共通部品 | [shared/copy/ser-retry-procs.cpy](../../../shared/copy/ser-retry-procs.cpy)（SER-RETRY）、[shared/copy/double-entry-helper-procs.cpy](../../../shared/copy/double-entry-helper-procs.cpy)、[shared/copy/aud-write-api.cpy](../../../shared/copy/aud-write-api.cpy)（AUD-WRITE）、[shared/copy/aud-drain-procs.cpy](../../../shared/copy/aud-drain-procs.cpy) | 直列化リトライ・複式・監査 |
| システム勘定 | cash=`0010010000001` / clearing=`0010010000002` | 起動時に存在検証 |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH | TO-BE Java |
|---|---|---|---|---|
| `TXPOST-RUN-BATCH` | [src/txpost-run-batch.sqb](../../../subsystems/12-txnpost/src/txpost-run-batch.sqb) | 複式記帳本体 | `INIT-SER-CONFIG`, `DB-CONNECT`, `DRAIN-AUDIT-OUTBOX`, `VERIFY-SYSTEM-ACCOUNTS`, `CHECK-I4-MONOTONICITY`, `PROCESS-LOOP`, `POST-ONE-TXN`, `MAP-DR-CR`, `WRITE-OUTBOX` | `PostStage.post()` |
| `TXPOST-REVERSE` | [src/txpost-reverse.sqb](../../../subsystems/12-txnpost/src/txpost-reverse.sqb) | 逆仕訳生成 | `FIND-ORIG`, `BUILD-REVERSAL`, `WRITE-OUTBOX` | `PostStage.reverse()` |
| `TXPOST-REPORT-SUMMARY` | [src/txpost-report-summary.cob](../../../subsystems/12-txnpost/src/txpost-report-summary.cob) | サマリレポート | `MAIN-LOGIC` | `PostReport`（任意） |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | バッチ（パイプライン最終ステージの関数呼び出し） |
| 実行契機 | 11 のソート/マージ完了後 |
| 多重度・冪等性 | **DB副作用あり**。冪等は `transactions.txn_id` 一意制約（I1チェック）で担保。多重起動は `pg_advisory_lock`（横断 §6-E） |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[起動シーケンス]
1. INIT-SER-CONFIG    : 直列化リトライ設定（delay=10ms, mult=2, maxDelay=2000ms, maxAttempts=5）
2. DB-CONNECT         : PostgreSQL 接続
3. DRAIN-AUDIT-OUTBOX : 前回未送信の監査イベントを送出（起動時ドレイン）
4. VERIFY-SYSTEM-ACCOUNTS : cash/clearing 勘定の存在検証（無ければ FATAL=16）
5. CHECK-I4-MONOTONICITY  : 取引ID単調増加性（I4）検証

[明細ループ PROCESS-LOOP（ready 1件ごと）]
6. POST-ONE-TXN（SERIALIZABLE トランザクション内）
   a. CHECK-I1  冪等性：txn_id 既存なら ALREADY-POSTED-SKIP（E024）
   b. CHECK-I5  禁止操作：区分別の不正操作を弾く
   c. CHECK-CAT-30-PAYEE 振替の受取口座検証
   d. MAP-DR-CR 借方/貸方勘定の決定（2.2）
   e. CHECK-I3  残高：払出側の残高不足を弾く（E021）
   f. INSERT transactions / postings(DR,CR) / UPDATE balances（FOR UPDATE ロック順序）
   g. WRITE-OUTBOX  audit_outbox へ event_key=MD5(txn_id||posting_id)
   h. COMMIT（CLASSIFY-SQL-RESULT で OK/DEFER/FATAL/INDOUBT/CONFLICT 判定 → リトライ FSM）
7. カウンタ集計・サマリ
```

### 2.2 借方/貸方マッピング（`MAP-DR-CR`）

| 区分 | 取引 | 借方（DR） | 貸方（CR） |
|---|---|---|---|
| 10 | 入金 | cash | payer |
| 20 | 出金 | payer | cash |
| 30 | 振替 | payer | payee |
| 40 | 送金（為替） | payer | clearing |
| 50 | 利息 | （利息計上ルール） | payer |
| 60 | 手数料 | payer | （手数料勘定） |

> 不変条件: 各取引で **借方合計 = 貸方合計**。検証は [tests/unit/check-postings-sum.sh](../../../subsystems/12-txnpost/tests/unit/check-postings-sum.sh)（DR=CR）相当を TO-BE でも維持。

### 2.3 主要チェック（不変条件 I1–I5・禁止操作）

| ID | 名称 | 内容 | 違反コード |
|---|---|---|---|
| I1 | 冪等性 | `txn_id` が既に記帳済なら二重記帳しない | E024（スキップ） |
| I3 | 残高充足 | 払出後残高が負にならない | E021 |
| I4 | 単調増加 | 取引ID単調増加性（起動時検証） | FATAL(16) |
| I5 | 禁止操作 | 区分別の不正操作 | 下表 |

**禁止操作（I5 / `CHECK-I5`）**

| 条件 | 違反コード |
|---|---|
| 払出禁止口座（P）からの払出 | E023 |
| 解約口座（C）への操作 | E005 |
| 停止口座（S）への操作 | E006 |
| 休眠口座（D）への入金以外 | E022（休眠繰延 deposit のみ可） |

### 2.4 排他・トランザクション制御（最重要・厳密保存）

| 要素 | AS-IS | TO-BE | 保存方針 |
|---|---|---|---|
| 分離レベル | `SET TRANSACTION ISOLATION LEVEL SERIALIZABLE` | `@Transactional(isolation = SERIALIZABLE)` | **厳密保存** |
| リトライ | SER-RETRY FSM（[ser-retry-procs.cpy](../../../shared/copy/ser-retry-procs.cpy)）: delay=10ms, mult=2, maxDelay=2000ms, maxAttempts=5 | `@Retryable(backoff=@Backoff(delay=10, multiplier=2, maxDelay=2000), maxAttempts=5)` | **厳密保存** |
| 直列化失敗分類 | `CLASSIFY-SQL-RESULT`: OK/DEFER/FATAL/INDOUBT/CONFLICT | SQLState `40001`(serialization_failure)/`40P01`(deadlock) を CONFLICT としてリトライ | **厳密保存** |
| ロック順序 | `SELECT ... FOR UPDATE` を口座番号昇順 | 同一順序で `FOR UPDATE`（デッドロック回避） | **厳密保存** |
| アウトボックス | 同一 TX 内で `audit_outbox` INSERT（event_key=MD5(txn_id‖posting_id)） | 同一 `@Transactional` 内で INSERT | **厳密保存**（横断 §3.4） |
| IN-DOUBT 解決 | コミット応答不明時に状態照会で解決 | 同一ロジックを保持。`IN-DOUBT-RESOLVED` を計上 | **厳密保存** |
| 多重起動防止 | systemd 単一起動 | `pg_advisory_lock`（横断 §6-E） | 方針変更 |

> ⚠️ 本ステージは横断方針で「段境界で全体ロールバックしない」: 12 のコミットは取引単位で確定する。パイプライン全体は 12 のコミット済を前提に冪等再実行（I1）で安全に再開する（横断 §6-A）。

### 2.5 エラー処理・ログ・理由コード

| コード | 意味 |
|---|---|
| E004 | 取引データ不正 |
| E005 | 解約口座操作 |
| E006 | 停止口座操作 |
| E020 | 口座不存在 |
| E021 | 残高不足 |
| E022 | 休眠口座（繰延） |
| E023 | 払出禁止口座 |
| E024 | 既記帳（冪等スキップ） |
| E025 | （区分別不正） |
| E026 | （区分別不正） |

監査は AUD-WRITE（[aud-write-api.cpy](../../../shared/copy/aud-write-api.cpy)）でアウトボックスへ。ドレインは [aud-drain-procs.cpy](../../../shared/copy/aud-drain-procs.cpy)。

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/tx-post-api.cpy](../../../subsystems/12-txnpost/copy/api/tx-post-api.cpy)

**記帳（`TXPOST-RUN-INPUT`）**

| COBOLフィールド名 | PIC | 必須 | 説明 |
|---|---|---|---|
| `TXPR-IN-BATCH-ID` | `X(14)` | ✓ | バッチID |
| `TXPR-IN-BUSINESS-DATE` | `9(8)` | ✓ | 業務日 |
| `TXPR-IN-READY-FILENAME` | `X(80)` | ✓ | ready 入力。TO-BE: `List<ReadyTxn>` |
| `TXPR-IN-ERROR-FILENAME` | `X(80)` | ✓ | ハード却下出力 |
| `TXPR-IN-RECON-DEFER-FILENAME` | `X(80)` | ✓ | 突合繰延出力 |
| `TXPR-IN-CHECKPOINT-FILENAME` | `X(80)` | — | 未使用（横断 §6-A 廃止） |
| `TXPR-IN-DORMANCY-FILENAME` | `X(80)` | ✓ | 休眠繰延出力 |

**逆仕訳（`TXPOST-REVERSE-INPUT`）**

| COBOLフィールド名 | PIC | 必須 | 説明 |
|---|---|---|---|
| `TXPV-IN-ORIG-TXN-ID` | `X(18)` | ✓ | 取消対象の元取引ID |
| `TXPV-IN-REVERSAL-REASON` | `X(80)` | ✓ | 取消理由 |
| `TXPV-IN-OPERATOR-ID` | `X(20)` | ✓ | 操作者ID |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 入力ファイル | `ready.dat`（[fd-txn-ready-in.cpy](../../../subsystems/12-txnpost/copy/private/fd-txn-ready-in.cpy)） | 600byte 順次（payer+seq 昇順） | payer+seq | TO-BE: 11 の `List<ReadyTxn>` |
| テーブル | `accounts`/`balances` | PostgreSQL | account_no | 残高・状態参照 |
| テーブル | `transactions` | PostgreSQL | txn_id（一意） | I1 冪等チェック |

### 3.3 前提・事前条件

- システム勘定（cash/clearing）が存在すること。
- ready が payer+seq 昇順であること（11 の保証）。
- PostgreSQL スキーマが Flyway で適用済（V1–V7）。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

**記帳（`TXPOST-RUN-OUTPUT`）**

| COBOLフィールド名 | PIC | 説明 |
|---|---|---|
| `TXPR-STATUS` | `X(2)` | 戻り値コード（88: OK00/PARTIAL-RECON04/INVALID08/IO-FAIL12/FATAL16） |
| `TXPR-RECORDS-READ` | `9(7)` | 読込件数 |
| `TXPR-RECORDS-ATTEMPTED` | `9(7)` | 記帳試行件数 |
| `TXPR-RECORDS-POSTED` | `9(7)` | 記帳成功件数 |
| `TXPR-ALREADY-POSTED-SKIPPED` | `9(7)` | 冪等スキップ件数（E024） |
| `TXPR-HARD-REJECTED` | `9(7)` | ハード却下件数 |
| `TXPR-RECON-DEFERRED` | `9(7)` | 突合繰延件数 |
| `TXPR-IN-DOUBT-RESOLVED` | `9(7)` | IN-DOUBT 解決件数 |
| `TXPR-DORMANCY-DEFERRED` | `9(7)` | 休眠繰延件数 |
| `TXPR-REASON-E004`〜`E026` | `9(7)` | 理由コード別件数 |
| `TXPR-DURATION-SEC` | `9(5)` | 実行秒数 |

**逆仕訳（`TXPOST-REVERSE-OUTPUT`）**

| COBOLフィールド名 | PIC | 説明 |
|---|---|---|
| `TXPV-STATUS` | `X(2)` | 88: OK00/ORIG-NOT-FOUND04/INVALID08/IO-FAIL12/FATAL16 |
| `TXPV-NEW-RV-TXN-ID` | `X(18)` | 生成した逆仕訳取引ID |
| `TXPV-IN-DOUBT-RESOLVED` | `X(1)` | IN-DOUBT 解決有無 |

### 4.2 出力データ更新

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| テーブル | `transactions` | INSERT | txn_id, batch, 区分, 金額, 状態 | 一意制約で冪等 |
| テーブル | `postings` | INSERT×2 | DR/CR の仕訳行 | 借方=貸方 |
| テーブル | `balances` | UPDATE | 口座残高 | `FOR UPDATE` 昇順ロック |
| テーブル | `audit_outbox` | INSERT | event_key=MD5(txn_id‖posting_id) | 同一 TX 内 |
| 繰延出力 | recon-defer / dormancy | WRITE | 繰延レコード | TO-BE: `List` |

### 4.3 後続・事後条件

- 記帳済取引が `transactions`/`postings`/`balances` に原子的反映。
- `audit_outbox` のイベントを 21-audit がドレイン。
- 当日確定分は翌日 recon（11）用に `daily_ready` へ登録（横断 §6-B）。

---

## 5. レコード定義

ready 入力: [copy/private/fd-txn-ready-in.cpy](../../../subsystems/12-txnpost/copy/private/fd-txn-ready-in.cpy)（明細は [shared/copy/ws-txn-decoded-record.cpy](../../../shared/copy/ws-txn-decoded-record.cpy) と同系）。
直列化状態: [shared/copy/ser-retry-state.cpy](../../../shared/copy/ser-retry-state.cpy)、監査状態: [shared/copy/aud-drain-state.cpy](../../../shared/copy/aud-drain-state.cpy)。

| テーブル | 主キー | 主な列 |
|---|---|---|
| `transactions` | txn_id | batch_id, business_date, category, amount_jpy, status |
| `postings` | posting_id | txn_id, account_no, dr_cr, amount_jpy |
| `balances` | account_no | balance_jpy, updated_at |
| `audit_outbox` | event_id | event_key(unique), payload, sent_flag |

> 詳細は [db/migration/V1__initial_schema.sql](../../../db/migration/V1__initial_schema.sql) / [V7__audit_outbox.sql](../../../db/migration/V7__audit_outbox.sql) を参照。

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/OCESQL） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | 記帳先 | OCESQL 埋込 SQL → PostgreSQL | Spring Data JDBC / JdbcTemplate → 同 PostgreSQL | **記帳ロジック厳密保存** |
| 2 | SERIALIZABLE | `SET TRANSACTION ISOLATION LEVEL SERIALIZABLE` | `@Transactional(isolation=SERIALIZABLE)` | 厳密保存 |
| 3 | リトライ FSM | SER-RETRY（手書き指数バックオフ） | `@Retryable`（delay=10,mult=2,maxDelay=2000,maxAttempts=5） | 厳密保存（§2.4） |
| 4 | 直列化失敗分類 | `CLASSIFY-SQL-RESULT` | SQLState 40001/40P01 → リトライ。その他は分類どおり | 厳密保存 |
| 5 | アウトボックス | 同一 TX 内 INSERT、event_key=MD5 | 同一 `@Transactional` 内 INSERT、同一 event_key | 厳密保存 |
| 6 | 起動ドレイン | `DRAIN-AUDIT-OUTBOX` | 起動時に未送信イベントをドレイン（@PostConstruct or ジョブ前段） | 保存 |
| 7 | チェックポイント | `.ckpt` 再開 | **廃止**。再開は I1 冪等で安全 | 横断 §6-A |
| 8 | 多重起動防止 | systemd 単一起動 | `pg_advisory_lock` | 横断 §6-E |
| 9 | 借方=貸方検証 | check-postings-sum.sh | ステージ後アサーション + テスト移植 | 維持 |

### TO-BE Java 関数マッピング

```java
public final class PostStage implements PipelineStage<SortMergeResult, PostResult> {
    StageResult<PostResult> execute(SortMergeResult in, BatchContext ctx);

    // POST-ONE-TXN 相当：1取引を SERIALIZABLE で記帳（I1/I3/I5 → DR/CR → outbox）
    @Retryable(retryFor = TransientDataAccessException.class,
               backoff = @Backoff(delay = 10, multiplier = 2, maxDelay = 2000),
               maxAttempts = 5)
    @Transactional(isolation = Isolation.SERIALIZABLE)
    PostOutcome postOne(ReadyTxn txn, BatchContext ctx);

    // MAP-DR-CR 相当
    private DrCrPair mapDrCr(ReadyTxn txn);

    // WRITE-OUTBOX 相当：event_key = MD5(txn_id || posting_id)
    private void writeOutbox(long txnId, long postingId);

    // TXPOST-REVERSE 相当
    StageResult<ReverseResult> reverse(ReverseRequest req, BatchContext ctx);
}
```

> リトライは取引単位（`postOne`）に閉じる。`execute` 全体ではなく、各取引のコミット/再試行が独立。

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | DB アクセス層（JdbcTemplate / Spring Data JDBC / JPA） | SERIALIZABLE + 明示ロック順序を制御しやすい JdbcTemplate を第一候補（横断 §6 論点） | — | — |
| 2 | `@Retryable` と `@Transactional` の合成順序 | リトライがトランザクション境界の外側になるよう構成（プロキシ順序を検証） | — | — |
| 3 | IN-DOUBT 解決ロジックの Java 実装方式 | コミット応答不明時の状態照会を再現。AS-IS の判定条件を精読 | — | — |
| 4 | E025/E026 の発生条件詳細 | AS-IS コードを精読し条件を確定 | — | — |
| 5 | `daily_ready` 登録の責務（12 or オーケストレータ） | 横断 §6-B とあわせ確定 | — | — |
| 6 | 既存シェルテスト（check-postings-sum 等）の移植 | JUnit + Testcontainers(PostgreSQL) へ移植 | — | — |

---

*テンプレートバージョン: 1.0 / 参照: [specs-asis/02-transaction-pipeline.md](../specs-asis/02-transaction-pipeline.md), [99-pipeline-orchestration-design.md](99-pipeline-orchestration-design.md), [db/migration/V7__audit_outbox.sql](../../../db/migration/V7__audit_outbox.sql)*

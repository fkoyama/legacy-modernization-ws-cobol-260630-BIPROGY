# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `09-accountlifecycle` |
| ディレクトリ | [subsystems/09-accountlifecycle/](../../../subsystems/09-accountlifecycle/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/alc-api.cpy](../../../subsystems/09-accountlifecycle/copy/api/alc-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

口座のライフサイクル（開設・状態遷移・休眠化・再活性）を管理する。新規開設時の口座番号採番、状態機械に基づく状態遷移、休眠スキャンバッチを提供する。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 運用バッチ / オンライン口座開設 | 開設・状態変更・休眠スキャン |
| 下流（呼び出し先） | 08-account（fd-account.cpy 共用） | 口座レコードの読み書き |
| 下流（呼び出し先） | 21-audit（AUD-WRITE） | 開設・状態遷移の監査記録 |
| 下流（呼び出し先） | 01-calendar | 休眠日計算・営業日判定 |
| 参照データ | `account.idx`（ISAM） / `accounts`（PG, TO-BE） | 08-account と共用 |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `ALC-OPEN` | [src/alc-open.cob](../../../subsystems/09-accountlifecycle/src/alc-open.cob) | 新規開設（番号採番=支店+商品+連番、初期状態`P`、監査記録） | `MAIN-LOGIC` ほか |
| `ALC-CHANGE-STATE` | [src/alc-change-state.cob](../../../subsystems/09-accountlifecycle/src/alc-change-state.cob) | 状態遷移（状態機械、監査記録） | `MAIN-LOGIC` |
| `ALC-DORMANCY-SCAN` | [src/alc-dormancy-scan.cob](../../../subsystems/09-accountlifecycle/src/alc-dormancy-scan.cob) | 休眠化バッチ（基準日=業務日-730日、`TRANSITION-TO-D`） | `MAIN-LOGIC` |
| `ALC-REACTIVATION-SCAN` | [src/alc-reactivation-scan.cob](../../../subsystems/09-accountlifecycle/src/alc-reactivation-scan.cob) | 再活性スキャン（現状スタブ、常に`04`を返す） | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL: OPEN/CHANGE） ／ バッチ・タイマー（DORMANCY-SCAN, [systemd/practice-bank-dormancy-scan.timer](../../../systemd/practice-bank-dormancy-scan.timer)） |
| 実行契機 | 口座開設要求 / 状態変更要求 / 日次（休眠スキャン） |
| 多重度・冪等性 | 更新系は監査記録を伴う。休眠スキャンは候補抽出→遷移（冪等運用前提） |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[ALC-OPEN]
1. 入力検証（顧客ID・商品・支店・開設日）
2. 口座番号採番（支店+商品+連番、連番は 9000000 から探索）
3. 初期状態 'P'（申込中）で WRITE
4. 21-audit へ開設を監査記録 → ALC-OPEN-ACCT-NUMBER を返す

[ALC-CHANGE-STATE]
1. 口座を READ → ALC-CHANGE-FROM-STATUS 取得
2. アクションコードに応じ状態機械で遷移可否を判定
3. 許可なら REWRITE → 監査記録（不許可は 08）

[ALC-DORMANCY-SCAN]
1. 基準日=業務日-730日 を算出
2. 候補口座を走査し TRANSITION-TO-D（A→D）
3. 遷移件数/スキップ件数を集計

[ALC-REACTIVATION-SCAN]
1. スタブ（常に 04 を返す）
```

### 2.2 主要ロジック・業務ルール（状態遷移: ALC-CHANGE-STATE）

| アクション | コード | 遷移 | 備考 |
|---|---|---|---|
| 有効化 | `AC` | P→A | |
| 取消 | `CN` | P→C | |
| 停止 | `SU` | A/D→S | 理由テキスト必須 |
| 停止解除 | `LS` | S→A | |
| 解約 | `CL` | A/D→C | |
| 強制解約 | `FC` | 非C→C | 理由テキスト必須 |

その他ルール:

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 口座番号採番 | 13桁=支店(3)+商品(3)+連番(7)。連番は 9000000 から探索 |
| 2 | 初期状態 | OPEN 直後は `P`（申込中） |
| 3 | 休眠基準日 | 業務日 - 730日（2年） |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 開設・遷移・スキャンに成功 |
| `04` | 該当なし | 対象口座なし / 休眠候補なし / 再活性スタブ |
| `08` | 入力不正 | 状態遷移不許可・必須理由欠落・入力不正 |
| `12` | I/O失敗 | 索引I/Oエラー（OPEN/CHANGE/SCAN） |
| `16` | 致命的エラー | 想定外の異常 |

> 各IFの戻り値: OPEN=`00/04/08/12/16`、CHANGE=同左、DORMANCY-SCAN=`00/04/12/16`、REACTIVATION-SCAN=同形（スタブ、常に`04`）

### 2.4 排他・トランザクション制御

`ALC-OPEN`/`ALC-CHANGE-STATE` は口座 WRITE/REWRITE と監査記録をセットで実施。TO-BE では PG トランザクション境界内で監査アウトボックスへ記録する想定。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 状態遷移不許可 | 戻り値`08`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |
| 開設・遷移 | 監査記録 | [shared/copy/aud-write-api.cpy](../../../shared/copy/aud-write-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/alc-api.cpy](../../../subsystems/09-accountlifecycle/copy/api/alc-api.cpy)

**OPEN**

| COBOLフィールド名 | PIC | 必須 | 説明 |
|---|---|---|---|
| `ALC-OPEN-CUST-ID` | `9(10)` | ✓ | 顧客ID |
| `ALC-OPEN-PRODUCT-CODE` | — | ✓ | 商品コード |
| `ALC-OPEN-BRANCH-CODE` | — | ✓ | 支店コード |
| `ALC-OPEN-OPENED-DATE` | — | ✓ | 開設日 |
| `ALC-OPEN-OVERDRAFT-LIMIT` | `S9(15) COMP-3` | △ | 当座貸越枠 |
| `ALC-OPEN-TERM-DAYS` | — | △ | 預入期間 |

**CHANGE**

| COBOLフィールド名 | PIC | 必須 | 説明 |
|---|---|---|---|
| `ALC-CHANGE-ACCT-NUMBER` | — | ✓ | 口座番号 |
| `ALC-CHANGE-ACTION-CODE` | — | ✓ | アクション（88条件 `ALC-ACT-*`） |
| `ALC-CHANGE-REASON-TEXT` | `X(80)` | △ | 理由（SU/FC で必須） |
| `ALC-CHANGE-BUSINESS-DATE` | — | ✓ | 業務日 |

**DORMANCY-SCAN / REACTIVATION-SCAN**

| COBOLフィールド名 | 必須 | 説明 |
|---|---|---|
| `ALC-DORMANCY-BUSINESS-DATE` | ✓ | 業務日（基準日=業務日-730日） |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `account.idx` | 固定長 | `ACCT-REC-NUMBER`(9(13)) | 08-account と共用 |
| テーブル | `accounts` | PostgreSQL | 口座番号(13) | TO-BE |

### 3.3 前提・事前条件

- `account.idx` が LOAD 済みであること。
- OPEN 時、顧客・商品・支店が存在すること（上流で確認）。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

**OPEN**

| COBOLフィールド名 | 説明 |
|---|---|
| 戻り値（`00/04/08/12/16`） | 結果コード |
| `ALC-OPEN-ACCT-NUMBER` | 採番された口座番号 |

**CHANGE**

| COBOLフィールド名 | 説明 |
|---|---|
| `ALC-CHANGE-FROM-STATUS` | 遷移前状態 |
| `ALC-CHANGE-TARGET-STATUS` | 遷移後状態 |

**DORMANCY-SCAN**

| COBOLフィールド名 | PIC | 説明 |
|---|---|---|
| `ALC-DORMANCY-TRANSITIONED` | `9(6)` | 休眠遷移件数 |
| `ALC-DORMANCY-SKIPPED` | `9(6)` | スキップ件数 |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `account.idx` | WRITE | 全項目 | ALC-OPEN（初期状態`P`） |
| 索引ファイル | `account.idx` | REWRITE | 状態（`ACCT-REC-STATUS`） | ALC-CHANGE-STATE / DORMANCY-SCAN |
| 監査ログ | 21-audit | 記録 | 開設・状態遷移 | OPEN/CHANGE |

### 4.3 後続・事後条件

- 開設・状態遷移時、監査アウトボックスにイベントが登録される。
- 状態が `A`→`D`（休眠）に遷移した口座は autodebit 対象から除外される。

---

## 5. レコード定義

レコードレイアウトは 08-account の [copy/private/fd-account.cpy](../../../subsystems/08-account/copy/private/fd-account.cpy) を共用。

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `ACCT-REC-NUMBER` | `9(13)` | 主キー | 口座番号（支店+商品+連番） |
| `ACCT-REC-CUST-ID` | `9(10)` | 副キー | 顧客ID |
| `ACCT-REC-STATUS` | `X(1)` | — | 状態 P/A/D/S/C/R |
| `ACCT-REC-DORMANCY-DATE` | `9(8)` | — | 休眠日 |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | 再活性スキャン | スタブ（常に`04`） | 未実装 | スコープ外（将来実装） |
| 2 | 番号採番 | 連番探索（9000000〜） | DBシーケンス等 | 採番ロジックの再設計を検討 |
| 3 | 監査記録 | AUD-WRITE 同期 | トランザクショナル・アウトボックス | 同一Txで `audit_outbox` へ |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | 再活性スキャンの実装 | 要件確定後に実装 | TBD | TBD |
| 2 | 口座番号採番の並行制御 | TO-BE での重複防止（シーケンス/採番表）を確定 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*

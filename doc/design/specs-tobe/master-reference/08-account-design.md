# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `08-account` |
| ディレクトリ | [subsystems/08-account/](../../../subsystems/08-account/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/acct-api.cpy](../../../subsystems/08-account/copy/api/acct-api.cpy)（4インターフェース定義） |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

口座番号をキーに口座マスタを参照・存在確認し、顧客の全口座一覧取得、休眠日更新を提供する。LOOKUP / EXISTS / LOOKUP-BY-CUSTOMER / UPDATE-DORMANCY の4インターフェースを持つ。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 09-accountlifecycle / 取引パイプライン | 口座参照・存在確認・休眠日更新 |
| 下流（呼び出し先） | 共有ログ（SHARED-LOG） | ロード処理等の構造化ログ |
| 参照データ | `account.idx`（ISAM） / `accounts`（PG, TO-BE） | 一次データ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `ACCT-LOAD` | [src/acct-load.cob](../../../subsystems/08-account/src/acct-load.cob) | シードを索引へロード（`COPY-FIELDS`でマッピング） | `MAIN-LOGIC` ほか |
| `ACCT-LOOKUP` | [src/acct-lookup.cob](../../../subsystems/08-account/src/acct-lookup.cob) | 口座番号で1件参照 | `MAIN-LOGIC` |
| `ACCT-EXISTS` | [src/acct-exists.cob](../../../subsystems/08-account/src/acct-exists.cob) | 存在確認＋状態/商品コード/有効フラグ | `MAIN-LOGIC` |
| `ACCT-LOOKUP-BY-CUSTOMER` | [src/acct-lookup-by-customer.cob](../../../subsystems/08-account/src/acct-lookup-by-customer.cob) | 顧客の全口座（副キー、最大50走査、挿入ソート、ページング） | `MAIN-LOGIC` |
| `ACCT-UPDATE-DORMANCY-DATE` | [src/acct-update-dormancy-date.cob](../../../subsystems/08-account/src/acct-update-dormancy-date.cob) | 休眠日更新（状態A/D、新日≥旧日を検証） | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） ／ ロードはバッチ |
| 実行契機 | API要求ごと ／ 初期セットアップ時（LOAD） |
| 多重度・冪等性 | 参照系は冪等・並行可。休眠日更新は条件付き REWRITE（NOOP判定あり） |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[ACCT-LOOKUP]
1. ACCT-LOOKUP-NUMBER を主キーに索引を READ
2. 該当なし→04 / I/O失敗→12
3. 出力エリアへ口座属性をセット

[ACCT-EXISTS]
1. 主キー READ → 存在Y/N、状態、商品コード、有効フラグ（状態=A なら Y）

[ACCT-LOOKUP-BY-CUSTOMER]
1. 副キー（顧客ID）で START → READ NEXT（最大50走査）
2. 挿入ソートし、最大20件を OCCURS に格納（ページング、MORE/LAST-ACCT）

[ACCT-UPDATE-DORMANCY-DATE]
1. 主キー READ → 状態が A/D かつ 新日≥旧日 を検証
2. 条件合致なら REWRITE、不要なら WAS-NOOP=Y
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 有効フラグ | EXISTS は状態=`A` のとき `ACCT-EXISTS-ACTIVE-FLAG`=`Y` |
| 2 | 顧客口座一覧 | 最大50走査・挿入ソート・最大20件返却・ページング（MORE/LAST-ACCT） |
| 3 | 休眠日更新 | 状態 A/D かつ 新日≥旧日でのみ更新。新日=旧日等は NOOP（`WAS-NOOP`=`Y`） |
| 4 | 重複警告 | LOOKUP-BY-CUSTOMER は重複検知時 `02` |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 参照・更新に成功 |
| `02` | 警告 | LOOKUP-BY-CUSTOMER の重複検知 |
| `04` | 該当なし | キー一致レコードなし |
| `08` | 入力不正 | パラメータ不正（状態不適合・新日<旧日等） |
| `10` | EOF | （該当時） |
| `12` | I/O失敗 | 索引I/Oエラー |
| `16` | 致命的エラー | 想定外の異常 |

> 各インターフェースの戻り値: LOOKUP=`00/04/08/12/16`、EXISTS=`00/04/08/12/16`、LOOKUP-BY-CUSTOMER=`00/02/04/08/12/16`、UPDATE-DORMANCY=`00/04/08/12/16`

### 2.4 排他・トランザクション制御

`ACCT-UPDATE-DORMANCY-DATE` は READ→REWRITE。TO-BE では PG トランザクション境界内で楽観/悲観ロックを検討。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 索引オープン/I/O失敗 | 戻り値`12`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/acct-api.cpy](../../../subsystems/08-account/copy/api/acct-api.cpy)

**LOOKUP**

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約 |
|---|---|---|---|---|
| `ACCT-LOOKUP-NUMBER` | `9(13)` | ✓ | 口座番号 | 13桁 |

**EXISTS**

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約 |
|---|---|---|---|---|
| `ACCT-EXISTS-NUMBER` | `9(13)` | ✓ | 口座番号 | 13桁 |

**LOOKUP-BY-CUSTOMER**

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約 |
|---|---|---|---|---|
| `LOOKUP-BY-CUST-CUST-ID` | `9(10)` | ✓ | 顧客ID | 10桁 |
| `LOOKUP-BY-CUST-MAX` | `9(2) COMP-3` | ✓ | 最大取得件数 | ≤20 |
| `LOOKUP-BY-CUST-START-AFTER` | `9(13)` | △ | カーソル（この口座番号以降） | 継続時 |

**UPDATE-DORMANCY**

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約 |
|---|---|---|---|---|
| `UPDATE-DORMANCY-ACCT-NUMBER` | `9(13)` | ✓ | 口座番号 | 13桁 |
| `UPDATE-DORMANCY-NEW-DATE` | `9(8)` | ✓ | 新休眠日 | 旧日以上 |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `account.idx` | 固定長 | `ACCT-REC-NUMBER`(9(13)) | 副キー `ACCT-REC-CUST-ID` |
| 入力ファイル | [data/](../../../subsystems/08-account/data/) | 行順次 | — | LOAD 対象 |
| テーブル | `accounts` | PostgreSQL | 口座番号(13) | TO-BE |

### 3.3 前提・事前条件

- `account.idx` が LOAD 済みであること。
- 休眠日更新は対象口座が A/D 状態であること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

**LOOKUP**

| COBOLフィールド名 | PIC | 説明 |
|---|---|---|
| `ACCT-LOOKUP-STATUS` | `9(2)` | 戻り値コード |
| `ACCT-LO-NUMBER` | `9(13)` | 口座番号 |
| `ACCT-LO-CUST-ID` | `9(10)` | 顧客ID |
| `ACCT-LO-PRODUCT-CODE` | `9(3)` | 商品コード |
| `ACCT-LO-BRANCH-CODE` | `9(3)` | 支店コード |
| `ACCT-LO-OPENED-DATE` | `9(8)` | 開設日 |
| `ACCT-LO-CLOSED-DATE` | `9(8)` | 解約日 |
| `ACCT-LO-STATUS` | `X(1)` | 状態（88条件 `ACCT-ST-*`） |
| `ACCT-LO-OVERDRAFT-LIMIT` | `S9(15) COMP-3` | 当座貸越枠 |
| `ACCT-LO-TERM-DAYS` | `9(4)` | 預入期間 |
| `ACCT-LO-DORMANCY-DATE` | `9(8)` | 休眠日 |
| `ACCT-LO-CREATED-TS` | `9(14)` | 作成TS |
| `ACCT-LO-UPDATED-TS` | `9(14)` | 更新TS |

**EXISTS**

| COBOLフィールド名 | 説明 |
|---|---|
| `ACCT-EXISTS-API-STATUS` | 戻り値コード |
| `ACCT-EXISTS-FOUND` | `Y`/`N` |
| `ACCT-EXISTS-STATUS-CODE` | 状態 |
| `ACCT-EXISTS-PRODUCT-CODE` | 商品コード |
| `ACCT-EXISTS-ACTIVE-FLAG` | 状態=`A`なら`Y` |

**LOOKUP-BY-CUSTOMER**

| COBOLフィールド名 | 説明 |
|---|---|
| `LOOKUP-BY-CUST-COUNT` | 取得件数 |
| `LOOKUP-BY-CUST-MORE` | `Y`/`N`（続きあり） |
| `LOOKUP-BY-CUST-LAST-ACCT` | 次ページカーソル |
| `LOOKUP-BY-CUST-RECORDS` | `OCCURS 20`（口座明細） |

**UPDATE-DORMANCY**

| COBOLフィールド名 | 説明 |
|---|---|
| `UPDATE-DORMANCY-PREV-DATE` | 更新前の休眠日 |
| `UPDATE-DORMANCY-WAS-NOOP` | `Y`/`N`（更新不要だったか） |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `account.idx` | REWRITE | 休眠日（`ACCT-REC-DORMANCY-DATE`） | UPDATE-DORMANCY（条件合致時のみ） |

### 4.3 後続・事後条件

- 休眠日更新後、09-accountlifecycle の休眠スキャンの判定基準に反映される。

---

## 5. レコード定義

レコードレイアウト: [copy/private/fd-account.cpy](../../../subsystems/08-account/copy/private/fd-account.cpy)

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `ACCT-REC-NUMBER` | `9(13)` | 主キー | 口座番号 |
| `ACCT-REC-CUST-ID` | `9(10)` | 副キー | 顧客ID |
| `ACCT-REC-PRODUCT-CODE` | `9(3)` | — | 商品コード |
| `ACCT-REC-BRANCH-CODE` | `9(3)` | — | 支店コード |
| `ACCT-REC-OPENED-DATE` | `9(8)` | — | 開設日 |
| `ACCT-REC-CLOSED-DATE` | `9(8)` | — | 解約日 |
| `ACCT-REC-STATUS` | `X(1)` | — | 状態 |
| `ACCT-REC-OVERDRAFT` | `S9(15) COMP-3` | — | 当座貸越枠 |
| `ACCT-REC-TERM-DAYS` | `9(4)` | — | 預入期間 |
| `ACCT-REC-DORMANCY-DATE` | `9(8)` | — | 休眠日 |
| `ACCT-REC-CREATED-TS` | `9(14)` | — | 作成TS |
| `ACCT-REC-UPDATED-TS` | `9(14)` | — | 更新TS |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | `ACCT-REC-OVERDRAFT` | 当座貸越枠あり | PG `accounts` に相当列なし | 読み取りスライスでは返さない（modernization-brief §7） |
| 2 | `ACCT-REC-TERM-DAYS` | 預入期間あり | PG に相当列なし | 同上 |
| 3 | API | CALL（acct-api.cpy） | `GET /api/v1/accounts/{accountNo}` | architecture.md 準拠 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | OVERDRAFT/TERM-DAYS | PGに無い項目の返却方針（null/省略）を確定 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*

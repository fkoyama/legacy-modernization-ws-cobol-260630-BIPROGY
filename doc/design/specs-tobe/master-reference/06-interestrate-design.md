# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `06-interestrate` |
| ディレクトリ | [subsystems/06-interestrate/](../../../subsystems/06-interestrate/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/irate-api.cpy](../../../subsystems/06-interestrate/copy/api/irate-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

商品コード・ティア（tier）・適用日をキーに金利マスタを参照し、適用金利を返す。レートはマイクロ単位（×1,000,000）で返却する。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 13-interestaccrual / 14-interestpost | 利息計算で金利を参照 |
| 下流（呼び出し先） | 共有ログ（SHARED-LOG） | ロード処理等の構造化ログ |
| 参照データ | `interestrate.idx`（ISAM） / `interest_rates`（PG, TO-BE） | 一次データ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `IRATE-LOAD` | [src/irate-load.cob](../../../subsystems/06-interestrate/src/irate-load.cob) | シードを索引へロード | `MAIN-LOGIC` ほか |
| `IRATE-LOOKUP` | [src/irate-lookup.cob](../../../subsystems/06-interestrate/src/irate-lookup.cob) | 商品+tier+適用日で参照（レートをマイクロ単位で返す） | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） ／ ロードはバッチ |
| 実行契機 | API要求ごと（LOOKUP） ／ 初期セットアップ時（LOAD） |
| 多重度・冪等性 | 参照系は冪等・並行可 |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[IRATE-LOOKUP]
1. 複合キー（商品+tier+適用日）で索引を参照
2. 該当なし→04
3. レート（S9(3)V9(4) COMP-3）をマイクロ単位（×1,000,000）に変換し IR-OUT-RATE-MICRO へ
4. 有効期間（EFF-FROM/TO）を出力
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | レート表現 | 内部 `S9(3)V9(4) COMP-3` を ×1,000,000 して `9(7)` で返す |
| 2 | 適用日 | 複合キーの一部として適用日（`IR-REC-EFF-FROM`）を含む |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 参照に成功 |
| `04` | 該当なし | 複合キー一致レコードなし |
| `16` | 致命的エラー | I/O失敗等 |

### 2.4 排他・トランザクション制御

参照のみのため排他制御なし。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 索引I/O失敗 | 戻り値`16`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/irate-api.cpy](../../../subsystems/06-interestrate/copy/api/irate-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `IR-IN-PRODUCT` | `X(3)` | ✓ | 商品コード | 3桁 |
| `IR-IN-TIER` | `9(2)` | ✓ | ティア | 2桁 |
| `IR-IN-EFFECTIVE` | `9(8)` | ✓ | 適用日 | YYYYMMDD |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `interestrate.idx` | 固定長 | `IR-REC-KEY`（商品+tier+適用日） | 複合キー |
| 入力ファイル | [data/](../../../subsystems/06-interestrate/data/) | 行順次 | — | LOAD 対象 |
| テーブル | `interest_rates` | PostgreSQL | 商品+tier+適用日 | TO-BE |

### 3.3 前提・事前条件

- `interestrate.idx` が LOAD 済みであること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `IR-OUT-STATUS` | `9(2)` | 戻り値コード | 全ケースで設定 |
| `IR-OUT-RATE-MICRO` | `9(7)` | 金利（×1,000,000） | 正常時。内部COMP-3から変換 |
| `IR-OUT-EFF-FROM` | `9(8)` | 有効開始日 | 正常時 |
| `IR-OUT-EFF-TO` | `9(8)` | 有効終了日 | 正常時 |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `interestrate.idx` | WRITE | 全レコード | LOAD 時のみ |

### 4.3 後続・事後条件

- 参照したレートが利息計算（13/14）で日割計算に利用される。

---

## 5. レコード定義

レコードレイアウト: [copy/private/fd-irate.cpy](../../../subsystems/06-interestrate/copy/private/fd-irate.cpy)

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `IR-REC-KEY` | （複合） | 主キー | 商品+tier+適用日 |
| `IR-REC-PRODUCT` | `X(3)` | 主キー構成 | 商品コード |
| `IR-REC-TIER` | `9(2)` | 主キー構成 | ティア |
| `IR-REC-EFF-FROM` | `9(8)` | 主キー構成 | 適用開始日 |
| `IR-REC-TIER-MIN` | `S9(15) COMP-3` | — | ティア下限残高 |
| `IR-REC-TIER-MAX` | `S9(15) COMP-3` | — | ティア上限残高 |
| `IR-REC-RATE` | `S9(3)V9(4) COMP-3` | — | 金利 |
| `IR-REC-EFF-TO` | `9(8)` | — | 適用終了日 |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | レート表現 | COMP-3 を ×1,000,000 で返す | NUMERIC（精度を保持） | マイクロ単位互換を維持するか要検討 |
| 2 | API | CALL（irate-api.cpy） | `GET /api/v1/interest-rates/{rateCode}` | architecture.md 準拠 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | レート単位 | TO-BE のAPI返却単位（micro / decimal）を確定 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*

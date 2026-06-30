variable "prefix" {
  description = "リソース名の接頭辞（小文字英数字推奨）"
  type        = string
  default     = "pbwscobol"
}

variable "location" {
  description = "Azure リージョン"
  type        = string
  default     = "japaneast"
}

variable "tags" {
  description = "全リソースに付与する共通タグ"
  type        = map(string)
  default = {
    project = "legacy-modernization-ws-cobol"
    env     = "workshop"
    iac     = "terraform"
  }
}

# ---- PostgreSQL Flexible Server ----
variable "pg_admin_user" {
  description = "PostgreSQL 管理者ユーザー名"
  type        = string
  default     = "pgadmin"
}

variable "pg_admin_password" {
  description = "PostgreSQL 管理者パスワード（本番では tfvars 等で上書きすること）"
  type        = string
  default     = "Workshop_Passw0rd!"
  sensitive   = true
}

variable "pg_db_name" {
  description = "作成するアプリ用データベース名"
  type        = string
  default     = "banking"
}

variable "pg_sku_name" {
  description = "PostgreSQL Flexible Server の SKU（最小 Burstable）"
  type        = string
  default     = "B_Standard_B1ms"
}

variable "pg_storage_mb" {
  description = "PostgreSQL ストレージ容量(MB)"
  type        = number
  default     = 32768
}

variable "pg_version" {
  description = "PostgreSQL バージョン"
  type        = string
  default     = "15"
}

# ---- Container App ----
variable "container_image" {
  description = "Container App のイメージ。空の場合は ACR 上の sample-accounts:latest を使用する"
  type        = string
  default     = ""
}

variable "container_target_port" {
  description = "Container App の Ingress ターゲットポート（FastAPI サンプルは 8080 でリッスン）"
  type        = number
  default     = 8080
}

variable "container_cpu" {
  description = "Container App のコンテナ CPU"
  type        = number
  default     = 0.25
}

variable "container_memory" {
  description = "Container App のコンテナメモリ"
  type        = string
  default     = "0.5Gi"
}

variable "container_min_replicas" {
  description = "Container App 最小レプリカ数"
  type        = number
  default     = 0
}

variable "container_max_replicas" {
  description = "Container App 最大レプリカ数"
  type        = number
  default     = 1
}

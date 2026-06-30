# =============================================================================
# Azure 最小構成 IaC（ワークショップ用・セキュリティ作り込みなし）
# 作成対象: Resource Group / ACR / Log Analytics + App Insights /
#           PostgreSQL Flexible Server / Container Apps Environment + Container App
# =============================================================================

locals {
  base_name = lower(var.prefix)
  # ACR 名は英数字のみ・グローバル一意性が必要なため suffix を付与
  acr_name = "${local.base_name}acr${random_string.suffix.result}"
  # container_image 未指定時は ACR 上のサンプルアプリ image を既定とする
  container_image = coalesce(var.container_image, "${azurerm_container_registry.main.login_server}/sample-accounts:latest")
}

resource "random_string" "suffix" {
  length  = 5
  special = false
  upper   = false
  numeric = true
}

# ---- 1. Resource Group -------------------------------------------------------
resource "azurerm_resource_group" "main" {
  name     = "${local.base_name}-rg"
  location = var.location
  tags     = var.tags
}

# ---- 2. Azure Container Registry (Standard / admin 有効) ---------------------
resource "azurerm_container_registry" "main" {
  name                = local.acr_name
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Standard"
  admin_enabled       = true
  tags                = var.tags
}

# ---- 3. Log Analytics + Application Insights ---------------------------------
resource "azurerm_log_analytics_workspace" "main" {
  name                = "${local.base_name}-law"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "PerGB2018"
  retention_in_days   = 30
  tags                = var.tags
}

resource "azurerm_application_insights" "main" {
  name                = "${local.base_name}-appi"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  workspace_id        = azurerm_log_analytics_workspace.main.id
  application_type    = "web"
  tags                = var.tags
}

# ---- 4. PostgreSQL Flexible Server (Burstable / public) ---------------------
resource "azurerm_postgresql_flexible_server" "main" {
  name                   = "${local.base_name}-pg"
  resource_group_name    = azurerm_resource_group.main.name
  location               = azurerm_resource_group.main.location
  version                = var.pg_version
  administrator_login    = var.pg_admin_user
  administrator_password = var.pg_admin_password
  sku_name               = var.pg_sku_name
  storage_mb             = var.pg_storage_mb
  zone                   = "1"

  # ワークショップ用: インターネット公開（プライベートネットワーク無し）
  public_network_access_enabled = true

  tags = var.tags
}

resource "azurerm_postgresql_flexible_server_database" "app" {
  name      = var.pg_db_name
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# Azure 内サービス（Container App など）からのアクセスを許可
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_azure" {
  name             = "AllowAzureServices"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# ワークショップ用: 全インターネットからのアクセスを許可（非セキュア）
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_all" {
  name             = "AllowAll"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "255.255.255.255"
}

# ---- 5. Container Apps Environment + Container App ---------------------------
resource "azurerm_container_app_environment" "main" {
  name                       = "${local.base_name}-cae"
  resource_group_name        = azurerm_resource_group.main.name
  location                   = azurerm_resource_group.main.location
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
  tags                       = var.tags
}

resource "azurerm_container_app" "api" {
  name                         = "${local.base_name}-api"
  resource_group_name          = azurerm_resource_group.main.name
  container_app_environment_id = azurerm_container_app_environment.main.id
  revision_mode                = "Single"
  tags                         = var.tags

  # ACR からプライベートに pull するための認証（admin 資格情報）
  registry {
    server               = azurerm_container_registry.main.login_server
    username             = azurerm_container_registry.main.admin_username
    password_secret_name = "acr-password"
  }

  secret {
    name  = "acr-password"
    value = azurerm_container_registry.main.admin_password
  }

  template {
    min_replicas = var.container_min_replicas
    max_replicas = var.container_max_replicas

    container {
      name   = "api"
      image  = local.container_image
      cpu    = var.container_cpu
      memory = var.container_memory

      # PostgreSQL 接続情報（COBOL/既存の環境変数規約に合わせる）
      env {
        name  = "PGHOST"
        value = azurerm_postgresql_flexible_server.main.fqdn
      }
      env {
        name  = "PGPORT"
        value = "5432"
      }
      env {
        name  = "PGUSER"
        value = var.pg_admin_user
      }
      env {
        name  = "PGPASSWORD"
        value = var.pg_admin_password
      }
      env {
        name  = "PGDATABASE"
        value = var.pg_db_name
      }
      env {
        name  = "APPLICATIONINSIGHTS_CONNECTION_STRING"
        value = azurerm_application_insights.main.connection_string
      }
    }
  }

  # 外部 Ingress 有効（インターネット公開）
  ingress {
    external_enabled = true
    target_port      = var.container_target_port
    transport        = "auto"

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }
}

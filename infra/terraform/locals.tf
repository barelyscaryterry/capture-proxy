locals {
  common_tags = merge({
    Project     = var.service_name
    Environment = var.environment
    ManagedBy   = "terraform"
  }, var.tags)

  name_prefix = "${var.service_name}-${var.environment}"
}

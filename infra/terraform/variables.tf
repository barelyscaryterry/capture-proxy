variable "aws_region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "service_name" {
  description = "Name of this service. Drives resource naming/tagging."
  type        = string
  default     = "capture-proxy"
}

variable "environment" {
  description = "Deployment environment (e.g. test, prod)."
  type        = string
  default     = "test"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC created for this service. Deliberately non-overlapping with test-service's 10.0.0.0/16, since the two will be peered."
  type        = string
  default     = "10.1.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets, one per AZ used."
  type        = list(string)
  default     = ["10.1.1.0/24", "10.1.2.0/24"]
}

variable "dynamodb_table_name" {
  description = "DynamoDB table name. Must match capture-proxy.ddb.table in application.properties."
  type        = string
  default     = "capture-proxy-requests"
}

variable "dynamodb_deletion_protection" {
  description = "Whether to enable deletion protection on the DynamoDB table."
  type        = bool
  default     = true
}

variable "ec2_instance_type" {
  description = "EC2 instance type for the app host."
  type        = string
  default     = "t3.micro"
}

variable "app_port" {
  description = "Port Tomcat listens on (actuator/health)."
  type        = number
  default     = 8080
}

variable "netty_port" {
  description = "Port the Netty capture server listens on."
  type        = number
  default     = 8443
}

variable "app_ingress_cidrs" {
  description = "CIDR blocks allowed to reach app_port and netty_port. VPC-internal only for now - the planned NLB/TLS-on-host work (see project CLAUDE.md) will change how netty_port is exposed."
  type        = list(string)
  default     = ["10.1.0.0/16"]
}

variable "root_volume_size_gb" {
  description = "Root EBS volume size (GiB) for the EC2 instance. Must be >= the AL2023 AMI's snapshot size (30GB as of writing)."
  type        = number
  default     = 30
}

variable "cloudwatch_namespace" {
  description = "CloudWatch metrics namespace."
  type        = string
  default     = "capture-proxy"
}

variable "tags" {
  description = "Extra tags merged into every resource's default tags."
  type        = map(string)
  default     = {}
}

variable "capture_log_retention_days" {
  description = "Retention for the captured-exchange log group. Captures are meant to be short-lived."
  type        = number
  default     = 1
}

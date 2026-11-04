terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Values (bucket/dynamodb_table/region) come from infra/terraform-bootstrap's
  # outputs. key/region below must be filled in to match after that bootstrap
  # is applied once - see infra/terraform-bootstrap/README.md.
  backend "s3" {
    bucket         = "capture-proxy-tfstate-966676241497"
    key            = "capture-proxy/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "capture-proxy-tfstate-lock"
    encrypt        = true
  }
}

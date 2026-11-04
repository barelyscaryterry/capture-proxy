terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Local state, deliberately - this config creates the S3 bucket + lock
  # table that the *main* infra/terraform config uses as its backend, so it
  # can't depend on that backend existing yet. Apply this once, by hand.
}

provider "aws" {
  region = var.aws_region
}

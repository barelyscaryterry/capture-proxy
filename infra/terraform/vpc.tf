data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${local.name_prefix}-vpc"
  }
}

resource "aws_subnet" "public" {
  for_each = { for idx, cidr in var.public_subnet_cidrs : tostring(idx) => cidr }

  vpc_id                  = aws_vpc.this.id
  cidr_block               = each.value
  availability_zone        = data.aws_availability_zones.available.names[tonumber(each.key) % length(data.aws_availability_zones.available.names)]
  map_public_ip_on_launch  = true

  tags = {
    Name = "${local.name_prefix}-public-${each.key}"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${local.name_prefix}-igw"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = {
    Name = "${local.name_prefix}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# Free Gateway endpoint - keeps DynamoDB traffic on the AWS backbone instead
# of routing out through the IGW, additive to (not a replacement for) public
# internet access.
resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.aws_region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id]

  tags = {
    Name = "${local.name_prefix}-ddb-endpoint"
  }
}

# TODO(test-service-peering): peer this VPC with test-service's (10.0.0.0/16)
# once test-service exposes a vpc_id output to reference, or wire via a
# direct SG reference if both land in the same VPC instead. See the
# corresponding TODO in security_groups.tf.

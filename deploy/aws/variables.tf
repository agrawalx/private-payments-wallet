variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-south-1"
}

variable "aws_profile" {
  description = "Named AWS CLI profile to deploy with (a dedicated project IAM user, NOT crawler-dev). Set up via `aws configure --profile <name>`."
  type        = string
  default     = "Private-payments-wallet"
}

variable "name" {
  description = "Name tag / prefix for resources"
  type        = string
  default     = "pp-services"
}

variable "instance_type" {
  description = "EC2 instance type. t3.micro (1GB, x86_64, free-tier eligible) is plenty for a ~0-traffic testnet demo running indexer+relayer+postgres."
  type        = string
  default     = "t3.micro"
}

variable "ssh_cidr" {
  description = "CIDR allowed to SSH. Lock to your IP, e.g. 1.2.3.4/32."
  type        = string
  default     = "0.0.0.0/0"
}

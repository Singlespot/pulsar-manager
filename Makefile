PROJECT_NAME := pulsar-manager
IMAGE_NAME := spt-pulsar-manager
PROJECT_VERSION := $(shell cat VERSION | tr -d '\n')
BUILD_DATE=$(shell date +%Y-%m-%d)
VCS_REF=$(shell git rev-parse --short HEAD)

SHELL := /bin/bash
BOLD := \033[1m
DIM := \033[2m
RESET := \033[0m
RED_BOLD :=\033[0;31m
GREEN_BOLD :=\033[1;32m

EGG := $(shell echo '$(PROJECT_NAME)' | tr - _).egg-info
RENAME:= $(shell which rename)

define success_msg
    (echo -e "$(GREEN_BOLD)$(1)$(RESET)"; true)
endef
define failure_msg
    (echo -e "$(RED_BOLD)$(1)$(RESET)"; $(2))
endef


# ***** BUILD *****

.PHONY: build_front
build_front:
	@cd front-end; npm run build:prod

.PHONY: build_back
build_back:
	export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
	./gradlew build -x test


# ***** DOCKER IMAGE *****

.PHONY: ecr_login
ecr_login:
	@aws ecr get-login-password --region eu-west-1 | docker login --username AWS --password-stdin 268324876595.dkr.ecr.eu-west-1.amazonaws.com

.PHONY: build_release_and_deploy
build_release_and_deploy:
	@docker build --build-arg BUILD_DATE=$(BUILD_DATE) --build-arg VCS_REF=$(VCS_REF) --build-arg VERSION=$(PROJECT_VERSION) -t $(IMAGE_NAME):$(PROJECT_VERSION) -f docker/Dockerfile .
	@docker tag $(IMAGE_NAME):$(PROJECT_VERSION) 268324876595.dkr.ecr.eu-west-1.amazonaws.com/$(IMAGE_NAME):$(PROJECT_VERSION)
	@docker tag $(IMAGE_NAME):$(PROJECT_VERSION) $(IMAGE_NAME):latest
	@docker push 268324876595.dkr.ecr.eu-west-1.amazonaws.com/$(IMAGE_NAME):$(PROJECT_VERSION)

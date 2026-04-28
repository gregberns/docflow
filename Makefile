.PHONY: build start up stop down test e2e eval

build:
	docker compose build

start:
	docker compose up -d

up: start

stop:
	docker compose down

down: stop

test:
	cd backend && ./gradlew check
	npm --prefix frontend run check

e2e:
	npm --prefix frontend run test:e2e

eval:
	cd backend && ./gradlew evalLive

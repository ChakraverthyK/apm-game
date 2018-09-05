#!/bin/bash

DOCKER_PREFIX="svrnm/apm-game-"
DOCKER_NETWORK="${DOCKER_PREFIX}network"

CONFIG=$1

if [ ! -f "${CONFIG}" ]
then
	CONFIG=config.yml
fi;

(
  cd master/ || exit
  npm install
);

for DIR in nodes/*;
do
  if [ -d $DIR ] ; then
    echo "Building ${DOCKER_PREFIX}`basename $DIR`..."
    docker build -t "${DOCKER_PREFIX}`basename $DIR`" $DIR;
  fi
done;

for DIR in loaders/*;
do
  if [ -d $DIR ] ; then
    docker build -t "${DOCKER_PREFIX}`basename $DIR`" $DIR;
  fi
done;

docker build -t "${DOCKER_PREFIX}machine" machine;

docker network create ${DOCKER_NETWORK}

node master/index.js ${CONFIG} ${DOCKER_PREFIX} ${DOCKER_NETWORK}

docker network rm ${DOCKER_NETWORK}

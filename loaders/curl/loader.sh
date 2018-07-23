#!/bin/sh
echo "Running CURL load in ${WAIT} seconds..."
sleep ${WAIT}
while [ true ]
do
  ID=`uuidgen`
  for URL in ${URLS}
  do
    /usr/bin/curl -s "${URL}?unique_session_id=${ID}"
  done
  sleep 0.1
done;
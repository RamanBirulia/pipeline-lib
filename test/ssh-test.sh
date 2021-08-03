#!/bin/sh
set -eux

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No color

FILE=./Jenkinsfile
if [ -f "$FILE" ]; then
   printf "${GREEN}Success!${NC}\\n"
else 
   printf "${RED}Failure!${NC}\\n"
   exit 1
fi

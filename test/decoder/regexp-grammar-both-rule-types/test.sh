#!/bin/bash

set -u

cat input | $JOSHUA/bin/joshua-decoder -c config > output 2> log

diff -u output output.gold > diff

if [ $? -eq 0 ]; then
    echo PASSED
    rm -f output log diff
    exit 0
else
    echo FAILED
    tail diff
    exit 1
fi


#!/bin/sh

SHA=96700ACD1128035FFEF5DC264DF87D5FEE45FF15E2A880708AE40675C9AD039E

freechains-host --port=8401 start /tmp/fc-01 &
sleep 1
freechains --port=8401 chains join '$chat' $SHA

for i in $(seq 1 1000); do
    freechains --port=8401 chain '$chat' post inline "Msg $i"
done

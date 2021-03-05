#!/usr/bin/env bash

echo
echo "=== TESTS-GENERAL ==="
echo

FC=/tmp/freechains
./clean.sh

PVT=6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
PUB=3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322

###############################################################################
echo "#### 1"

freechains-host start $FC/8400 --port=8400 &
sleep 0.5
freechains --host=localhost:8400 chains join "@!$PUB"
freechains-host --port=8400 now 0
g=`freechains --host=localhost:8400 chain "@!$PUB" genesis`
h=`freechains --host=localhost:8400 --sign=$PVT chain "@!$PUB" post inline Hello_World`
freechains --host=localhost:8400 chain "@!$PUB" get block "$h" > $FC/freechains-tests-get-1.out
freechains --host=localhost:8400 chain "@!$PUB" get block 0_F2FB65C093A02E86D717EB0AC1CE9DB201D9DC84E7E883E3A92FD561A193D4D8 > $FC/freechains-tests-get-0.out
hs=`freechains --host=localhost:8400 chain "@!$PUB" heads linked`
freechains --host=localhost:8400 chain "@!$PUB" get block "$g" > $FC/freechains-tests-gen.out
freechains --host=localhost:8400 chain "@!$PUB" get block "$hs" > $FC/freechains-tests-heads.out

diff -I local -I 1_ $FC/freechains-tests-gen.out   chk/freechains-tests-get-0.out || exit 1
diff -I local -I 1_ $FC/freechains-tests-get-0.out chk/freechains-tests-get-0.out || exit 1
diff -I local -I time -I hash $FC/freechains-tests-get-1.out chk/freechains-tests-get-1.out || exit 1
diff -I local -I time -I hash $FC/freechains-tests-heads.out chk/freechains-tests-get-1.out || exit 1

uuencode /bin/cat cat > /tmp/cat.uu
h=`freechains --host=localhost:8400 --sign=$PVT chain "@!$PUB" post file /tmp/cat.uu`
echo $h
freechains --host=localhost:8400 chain "@!$PUB" get payload "$h" | uudecode -o /tmp/cat
diff /tmp/cat /bin/cat || exit 1

###############################################################################
echo "#### 2"

freechains-host start $FC/8401 --port=8401 &
sleep 0.5
freechains-host --port=8401 now 0
freechains --host=localhost:8401 chains join "@!$PUB"
echo 111 | freechains --host=localhost:8400 --sign=$PVT chain "@!$PUB" post -
freechains --host=localhost:8400 --sign=$PVT chain "@!$PUB" post inline 222
freechains --host=localhost:8400 peer localhost:8401 send "@!$PUB"

diff -I local "$FC/8400/chains/@!$PUB/blocks/" "$FC/8401/chains/@!$PUB/blocks/" || exit 1
ret=`ls $FC/8400/chains/@!$PUB/blocks/ | wc`
if [ "$ret" != "     10      10     710" ]; then
  echo "$ret"
  exit 1
fi

###############################################################################
echo "#### 3"

rm -Rf $FC/8402
freechains-host start $FC/8402 --port=8402 &
sleep 0.5
freechains-host --port=8402 now 0
freechains --host=localhost:8402 chains join "@!$PUB"
freechains --host=localhost:8402 peer localhost:8400 recv "@!$PUB" &
P1=$!
freechains --host=localhost:8401 peer localhost:8402 send "@!$PUB" &
P2=$!
wait $P1 $P2
#sleep 10

diff -I local "$FC/8401/chains/@!$PUB/blocks/" "$FC/8402/chains/@!$PUB/blocks/" || exit 1
ret=`ls "$FC/8401/chains/@!$PUB/blocks/" | wc`
if [ "$ret" != "     10      10     710" ]; then
  echo "$ret"
  exit 1
fi

#exit 0

###############################################################################
###############################################################################
echo "#### 4"

for i in $(seq 1 50)
do
  freechains --host=localhost:8400 --sign=$PVT chain "@!$PUB" post inline $i
done
freechains --host=localhost:8400 peer localhost:8401 send "@!$PUB"
freechains --host=localhost:8400 peer localhost:8402 send "@!$PUB"

diff -I local $FC/8400/chains/@!$PUB/blocks/ $FC/8401/chains/@!$PUB/blocks/ || exit 1
diff -I local $FC/8401/chains/@!$PUB/blocks/ $FC/8402/chains/@!$PUB/blocks/ || exit 1
ret=`ls $FC/8401/chains/@!$PUB/blocks/ | wc`
if [ "$ret" != "    110     110    7900" ]; then
  echo "$ret"
  exit 1
fi

###############################################################################
echo "#### 5"

for i in $(seq 8411 8430)
do
  freechains-host start $FC/$i --port=$i &
  sleep 0.5
  freechains-host --port=$i now 0
  freechains --host=localhost:$i chains join "@!$PUB"
done

echo "#### 5.1"

for i in $(seq 8411 8420)
do
  freechains --host=localhost:8400 peer localhost:$i send "@!$PUB" &
done

sleep 35

echo "#### 5.2"

for i in $(seq 8411 8420)
do
  echo ">>> $i"
  diff -I local "$FC/8400/chains/@!$PUB/blocks/" "$FC/$i/chains/@!$PUB/blocks/" || exit 1
done

for i in $(seq 8411 8420)
do
  freechains --host=localhost:$(($i+10)) peer localhost:$i recv "@!$PUB" &
done
sleep 10

echo "#### 5.3"

for i in $(seq 8421 8425)
do
  freechains --host=localhost:$i peer localhost:$(($i+5))  send "@!$PUB" &
  freechains --host=localhost:$i peer localhost:$(($i+10)) send "@!$PUB" &
done
sleep 10

for i in $(seq 8421 8430)
do
  diff -I local "$FC/8400/chains/@!$PUB/blocks/" "$FC/$i/chains/@!$PUB/blocks/" || exit 1
done

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo

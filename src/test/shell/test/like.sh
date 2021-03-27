#!/usr/bin/env bash

echo
echo "=== TESTS-LIKE ==="
echo

FC=/tmp/freechains
./clean.sh

H0=--host=localhost:8400

PVT0=6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
PUB0=3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
SIG0=--sign=$PVT0

PUB1=E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369
PVT1=6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369
SIG1=--sign=$PVT1

###############################################################################

freechains-host start $FC/8400 --port=8400 &
sleep 0.5
freechains-host --port=8400 now 0
freechains $H0 chains join "#"
b1=`freechains $H0 $SIG1 chain "#" post inline pub1.1`
b2=`freechains $H0 $SIG0 chain "#" post inline pub0.2`

v0=`freechains $H0 chain "#" reps $PUB0`
diff <(echo $v0) <(echo "-1") || exit 1

freechains $H0 $SIG1 chain "#" like $b2

# b0 <- b1 <- l3
#          <- b2

v1=`freechains $H0 chain "#" reps $PUB0`
diff <(echo $v1) <(echo 0) || exit 1

# fail
f1=`freechains $H0 $SIG0 chain "#" like $b1`
diff <(echo $f1) <(echo "like author must have reputation") || exit 1

freechains-host --port=8400 now 8000000   # 2h

# fail
f1=`freechains $H0 $SIG0 chain "#" like $b1`
diff <(echo $f1) <(echo "like author must have reputation") || exit 1

freechains-host --port=8400 now 90000000  # 1d

b4=`freechains $H0 $SIG1 chain "#" post inline pub1.4`
l5=`freechains $H0 $SIG0 chain "#" like $b4`

j5=`freechains $H0 chain "#" get block $l5 inline`
d31=`jq ".like.hash" <(echo $j5)`
d32="\"$b4\""
diff <(echo $d31) <(echo $d32) || exit 1

freechains-host --port=8400 now 98000000  # 1d1h

l5x=`freechains $H0 $SIG0 chain "#" dislike "$b4" --why="hated it"`
j5x=`freechains $H0 chain "#" get block $l5x inline`

d5x=`jq ".like" <(echo $j5x)`
diff <(echo $d5x) <(echo "{ \"n\": -1, \"hash\": \"$b4\" }") || exit 1

v2=`freechains $H0 chain "#" reps $b4`
diff <(echo $v2) <(echo "0") || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
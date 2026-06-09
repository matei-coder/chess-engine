#!/bin/bash
cd "$(dirname "$0")"
java -cp out chess.Main uci

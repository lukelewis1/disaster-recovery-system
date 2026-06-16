#!/usr/bin/env bash
#
# run_experiments.sh — sweep the disaster-response simulator across configurations,
# maps, and responders, collecting results into results.csv.
#
# For every (config, map, responder) combo it runs the sim RUNS times, parsing
# people-saved / total and vehicles-remaining from stdout, and appends one CSV row
# per run. Progress is printed live as each run finishes.
#
# Usage: ./run_experiments.sh
set -euo pipefail

cd "$(dirname "$0")"

# ---- paths -----------------------------------------------------------------
CFG="cfg/sim.cfg"
OUT="results.csv"
CP="out:lib/jdom-2.0.6/*:lib/slf4j-api-2.1.0-alpha1.jar:lib/slf4j-simple-2.1.0-alpha1.jar"
RUNS=3
TIMEOUT=120   # seconds; kill a run that hangs (e.g. deadlock on the 100k-node map)

# run_with_timeout SECONDS CMD... — run CMD, kill it (and its children) if it exceeds SECONDS.
# Portable watchdog because macOS has no timeout/gtimeout. Returns 124 on timeout.
run_with_timeout() {
  local secs="$1"; shift
  "$@" &
  local cmd_pid=$!
  ( sleep "$secs"; kill -9 "$cmd_pid" 2>/dev/null ) &
  local wd_pid=$!
  if wait "$cmd_pid" 2>/dev/null; then
    kill "$wd_pid" 2>/dev/null; wait "$wd_pid" 2>/dev/null
    return 0
  else
    kill "$wd_pid" 2>/dev/null; wait "$wd_pid" 2>/dev/null
    return 124
  fi
}

# ---- fixed parameters (same every run) -------------------------------------
DURATION=200000
STARTUP_PERIOD=500000
VEHICLE_SPEED=0.2
STDOUT_MESSAGES=TRUE
NUM_RESCUES=20

# ---- sweep dimensions ------------------------------------------------------
RESPONDERS=(
  solution.algorithms.BFS
  solution.algorithms.Dijkstra
  solution.algorithms.BidirectionalDijkstra
  solution.algorithms.Hybrid
)
MAPS=(0 2 4 5)   # 0=map.20  2=map.2000  4=map.100000  5=manhattan

# config -> "ROAD_DAMAGE LOCATION_DAMAGE RESCUE_DURATION"
# (case lookup instead of an associative array — macOS ships bash 3.2, which has none)
CONFIGS=(0 1 2 3a 3b 3c sweep_0 sweep_1 sweep_2 sweep_3 sweep_4 sweep_5)
params_for() {
  case "$1" in
    0)       echo "0   0   0" ;;
    1)       echo "0.3 0   0" ;;
    2)       echo "0.3 0.1 0" ;;
    3a)      echo "0.3 0   500" ;;
    3b)      echo "0.3 0   1500" ;;
    3c)      echo "0.3 0   3000" ;;
    sweep_0) echo "0   0 0" ;;
    sweep_1) echo "0.1 0 0" ;;
    sweep_2) echo "0.2 0 0" ;;
    sweep_3) echo "0.3 0 0" ;;
    sweep_4) echo "0.4 0 0" ;;
    sweep_5) echo "0.5 0 0" ;;
  esac
}

# ---- build (skip if out/ already populated) --------------------------------
if [ ! -d out ] || [ -z "$(ls -A out 2>/dev/null)" ]; then
  echo "Compiling..."
  find src -name "*.java" > sources.txt
  javac -cp "lib/jdom-2.0.6/*:lib/slf4j-api-2.1.0-alpha1.jar:lib/slf4j-simple-2.1.0-alpha1.jar:." -d out @sources.txt
fi

# ---- csv header ------------------------------------------------------------
# Resume-friendly: keep an existing results.csv and skip rows already present.
# Delete results.csv to start fresh.
if [ ! -f "$OUT" ]; then
  echo "config,map,responder,road_damage,location_damage,rescue_duration,run,people_saved,total_people,rescue_rate,vehicles_remaining,vehicle_survival_rate" > "$OUT"
fi

tmp="$(mktemp)"

# Preserve the original sim.cfg and restore it when the script exits (even on Ctrl-C).
cfg_backup="$(mktemp)"
cp "$CFG" "$cfg_backup"
trap 'rm -f "$tmp"; cp "$cfg_backup" "$CFG"; rm -f "$cfg_backup"; echo "Restored $CFG to its original contents."' EXIT

# ---- main sweep ------------------------------------------------------------
for config in "${CONFIGS[@]}"; do
  read -r ROAD_DAMAGE LOCATION_DAMAGE RESCUE_DURATION <<< "$(params_for "$config")"

  for map in "${MAPS[@]}"; do
    for responder in "${RESPONDERS[@]}"; do
      for run in $(seq 1 "$RUNS"); do

        # resume: skip this run if its row is already in results.csv
        if grep -qF "$config,$map,$responder,$ROAD_DAMAGE,$LOCATION_DAMAGE,$RESCUE_DURATION,$run," "$OUT"; then
          continue
        fi

        # overwrite sim.cfg with this run's configuration
        cat > "$CFG" <<EOF
MAP=$map
RESPONDER_CLASS=$responder
DURATION=$DURATION
STARTUP_PERIOD=$STARTUP_PERIOD
VEHICLE_SPEED=$VEHICLE_SPEED
STDOUT_MESSAGES=$STDOUT_MESSAGES
NUM_RESCUES=$NUM_RESCUES
ROAD_DAMAGE=$ROAD_DAMAGE
LOCATION_DAMAGE=$LOCATION_DAMAGE
RESCUE_DURATION=$RESCUE_DURATION
EOF

        # run sim, capture stdout; kill + skip if it hangs past TIMEOUT
        if ! run_with_timeout "$TIMEOUT" java -Xmx2G -cp "$CP" sim.Simulator "$CFG" > "$tmp" 2>/dev/null; then
          echo "[$config] [map $map] [${responder##*.}] run $run/$RUNS — TIMEOUT after ${TIMEOUT}s, skipped." >&2
          echo "$config,$map,$responder,$ROAD_DAMAGE,$LOCATION_DAMAGE,$RESCUE_DURATION,$run,,,,," >> "$OUT"
          continue
        fi

        # parse: "* GRAND TOTAL OF PEOPLE SAVED IS X OUT OF Y. *"
        read -r people_saved total_people < <(
          grep -oE "GRAND TOTAL OF PEOPLE SAVED IS [0-9]+ OUT OF [0-9]+" "$tmp" \
            | grep -oE "[0-9]+" | paste -sd' ' -
        )
        # parse: "* X VEHICLES LOST OUT OF Z. *"
        read -r vehicles_lost total_vehicles < <(
          grep -oE "[0-9]+ VEHICLES LOST OUT OF [0-9]+" "$tmp" \
            | grep -oE "[0-9]+" | paste -sd' ' -
        )

        # default to 0 if a line was missing (sim crash / no rescues)
        people_saved=${people_saved:-0}
        total_people=${total_people:-0}
        vehicles_lost=${vehicles_lost:-0}
        total_vehicles=${total_vehicles:-0}

        vehicles_remaining=$(( total_vehicles - vehicles_lost ))

        rescue_rate=$(awk -v s="$people_saved" -v t="$total_people" \
          'BEGIN { printf (t>0 ? "%.1f" : "0.0"), (t>0 ? s/t*100 : 0) }')
        vehicle_survival_rate=$(awk -v r="$vehicles_remaining" -v t="$total_vehicles" \
          'BEGIN { printf (t>0 ? "%.1f" : "0.0"), (t>0 ? r/t*100 : 0) }')

        # append csv row
        echo "$config,$map,$responder,$ROAD_DAMAGE,$LOCATION_DAMAGE,$RESCUE_DURATION,$run,$people_saved,$total_people,$rescue_rate,$vehicles_remaining,$vehicle_survival_rate" >> "$OUT"

        # progress
        printf '[%s] [map %s] [%s] run %d/%d — %s%% saved, %d vehicles.\n' \
          "$config" "$map" "${responder##*.}" "$run" "$RUNS" "$rescue_rate" "$vehicles_remaining"

      done
    done
  done
done

echo "Done. Results -> $OUT"

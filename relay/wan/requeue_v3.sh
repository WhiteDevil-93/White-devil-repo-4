#!/bin/bash
# After recovery: submit best-friends V3 and move it ahead of the packs.
T=$(cat ~/.wanbot_token); H="Authorization: Bearer $T"; U=127.0.0.1:18900
for _ in $(seq 180); do grep -q RECOVER_DONE <(tail -1 ~/wan/recover.log 2>/dev/null) && break; sleep 20; done
grep -q "fallback=0" <(tail -1 ~/wan/recover.log) || { echo "recovery not on wanbot; V3 not queued"; exit 1; }
curl -s -H "$H" $U/jobs | grep -q bestfriends-night-v3 && { echo "V3 already queued"; exit 0; }
PACKS=$(curl -s -H "$H" $U/jobs | python3 -c "import json,sys; j=json.load(sys.stdin); j=j.get(\"jobs\",j) if isinstance(j,dict) else j; print(\" \".join(x[\"id\"] for x in sorted(j,key=lambda x:x.get(\"created\",0)) if x.get(\"status\")==\"queued\"))")
for j in $PACKS; do curl -s -o /dev/null -X POST -H "$H" $U/jobs/$j/cancel; done
python3 - "$T" <<PY
import json,sys,requests
spec=json.load(open("/home/ubuntu/wan/wan_chain_best-friends-night_merged.json"))
spec["chain_id"]="bestfriends-night-v3"
spec["runner"]={"model":"wan22-5b-turbo-lora","name":"best_friends_night_v3","seed":20260925}
r=requests.post("http://127.0.0.1:18900/jobs",json=spec,headers={"Authorization":f"Bearer {sys.argv[1]}"},timeout=60); print("v3",r.status_code,r.json().get("id"))
PY
sleep 2
for j in $PACKS; do curl -s -o /dev/null -X POST -H "$H" $U/jobs/$j/retry; done
echo "V3 queued after the current job; packs requeued: $PACKS"

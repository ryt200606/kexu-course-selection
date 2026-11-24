"""Join measured HTTP windows to docker stats and guest lock samples."""
import argparse
import csv
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
from statistics import mean, median


def read_samples(path):
    samples = []
    for line in path.read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        sample = {"utc": record["utc"]}
        for observed in record["observed"]:
            match = re.fullmatch(r"course34-v3-(api|database|frontend)-1\|([\d.]+)%\|(.+?) / .+", observed)
            if match:
                role, percent, memory = match.groups()
                sample[role + "CpuPct"] = float(percent)
                used = re.fullmatch(r"([\d.]+)(KiB|MiB|GiB|B)", memory)
                if used:
                    scale = {"KiB": 1/1024, "MiB": 1, "GiB": 1024, "B": 1/1048576}
                    sample[role + "MiB"] = round(float(used[1]) * scale[used[2]], 2)
            if observed.startswith("waiting|"):
                sample["waitingLocks"] = int(observed.partition("|")[2])
        samples.append(sample)
    return samples


def report_stage(stage, all_samples):
    rows = stage["rounds"]
    samples = []
    for row in rows:
        end = datetime.fromisoformat(row["endUtc"])
        begin = end - timedelta(seconds=row["seconds"])
        samples.extend(s for t, s in all_samples if begin <= t <= end)
    requests = sum(x["requests"] for x in rows)
    write_count = sum(x["writes"] for x in rows)
    refusals = sum(x["businessRefusals"] for x in rows)
    data = {"clients": stage["clients"], "passedEveryRound": stage["passed"],
            "requests":requests, "writes":write_count, "businessRefusals":refusals,
            "technicalErrors":sum(r["technicalErrors"] for r in rows),
            "businessRefusalRatePerWrite":round(refusals/max(1,write_count),6),
            "medianRoundRequestsPerSecond":median(r["requestsPerSecond"] for r in rows),
            "worstRoundReadP95Ms":max(r["read"]["p95Ms"] for r in rows),
            "worstRoundWriteP95Ms":max(r["write"]["p95Ms"] for r in rows),
            "maximumDiscrepancies":{key:max(r["mismatches"][key] for r in rows) for key in rows[0]["mismatches"]},
            "resourceSampleCount":len(samples)}
    for role in ("database", "api", "frontend"):
        counts = [s[role+"CpuPct"] for s in samples if role+"CpuPct" in s]
        memory = [s[role+"MiB"] for s in samples if role+"MiB" in s]
        if counts:
            data[role+"MeanCpuPct"] = round(mean(counts),2)
            data[role+"PeakCpuPct"] = max(counts)
        if memory:
            data[role+"PeakMiB"] = max(memory)
    waits = [s["waitingLocks"] for s in samples if "waitingLocks" in s]
    if waits:
        data["peakObservedWaitingLocks"] = max(waits)
        data["samplesWithWaitingLocks"] = sum(n>0 for n in waits)
    return data


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results",type=Path,required=True)
    parser.add_argument("--sample-dir",type=Path,required=True)
    args=parser.parse_args()
    files=("v2-uniform","v2-hot","uniform","hot")
    summary={"machine":"openEuler 4 vCPU / 8 GiB, API capped at 2 vCPU and Hikari 12",
             "measurement":"3 independent windows per level; throughput median across rounds; worst of the three round P95 values",
             "resourceTiming":"docker stats and pg_locks sampled approximately every 2-4 seconds, joined within measured UTC windows",
             "profiles":{}}
    sampled={}
    for name in files:
        data=json.loads((args.results/(name+".json")).read_text(encoding="utf-8"))
        if data["status"]!="MEASURED":
            raise ValueError("Incomplete run: "+name)
        source=data["resourceSamples"]
        if source not in sampled:
            sampled[source]=read_samples(args.sample_dir/source)
        series=[(datetime.fromisoformat(s["utc"]),s) for s in sampled[source]]
        summary["profiles"][name]={"highestPassing":data["highestPassing"],"firstNonPassing":data["firstNonPassing"],
            "sourceImage":data["sourceImage"],"stages":[report_stage(stage,series) for stage in data["stages"]]}
    (args.results/"resource-summary.json").write_text(json.dumps(summary,indent=2)+"\n",encoding="utf-8")
    with (args.results/"resource-samples.csv").open("w",encoding="utf-8",newline="") as out:
        cols=["trace","utc","apiCpuPct","databaseCpuPct","frontendCpuPct","apiMiB","databaseMiB","frontendMiB","waitingLocks"]
        writer=csv.DictWriter(out,fieldnames=cols)
        writer.writeheader()
        for trace,series in sampled.items():
            for row in series:
                writer.writerow({"trace":trace,**row})


if __name__=="__main__":
    main()

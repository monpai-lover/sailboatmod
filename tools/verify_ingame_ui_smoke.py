"""Assert native Minecraft UI smoke output; does not substitute static source checks for rendering."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    failures = []
    checks = 0

    def check(condition, message):
        nonlocal checks
        checks += 1
        if not condition:
            failures.append(message)

    def scrollbars(capture):
        check("scrollbarChecks" in capture, f'{capture["name"]}: missing scrollbar audit')
        for result in capture.get("scrollbarChecks", []):
            check(result.get("passed"), f'{capture["name"]}: {result["name"]}')
        for interaction in capture.get("scrollbarInteractions", []):
            for result in interaction.get("checks", []):
                check(result.get("passed"), f'{capture["name"]}: {result["name"]}')
        if "territoryAccessibility" in capture:
            check(capture["territoryAccessibility"].get("passed"),
                  f'{capture["name"]}: territory controls exceed window without scrolling')
        if "controlAccessibility" in capture:
            check(capture["controlAccessibility"].get("passed"),
                  f'{capture["name"]}: container controls are not reachable')

    market_file = args.directory / "all-ui-runtime-report.json"
    if market_file.exists():
        market = json.loads(market_file.read_text(encoding="utf-8"))
        check(market.get("status") == "COMPLETED", "market run did not complete")
        check(market.get("regressionChecksPassed"), "market input/selection regression failed")
        for capture in market.get("captures", []):
            scrollbars(capture)
            if "left" in capture:
                check(capture["left"] >= -1 and capture["left"] + capture["windowWidth"] <= capture["screenWidth"] + 1,
                      f'{capture["name"]}: window outside horizontal viewport')
            if "requestedGuiScale" in capture:
                check(capture["guiScale"] == capture["requestedGuiScale"], f'{capture["name"]}: requested GUI scale was clamped')
            for element in capture.get("visibleElements", []):
                if element.get("type") == "TextField":
                    check(element["width"] >= 24, f'{capture["name"]}: unusable input width {element["width"]}')
                    check(element["x"] >= -1 and element["x"] + element["width"] <= capture["screenWidth"] + 1,
                          f'{capture["name"]}: input outside horizontal viewport')

    container_file = args.directory / "container-ui-runtime-report.json"
    if container_file.exists():
        containers = json.loads(container_file.read_text(encoding="utf-8"))
        check(containers.get("status") == "COMPLETED", "container run has failures or is incomplete")
        for probe in containers.get("homeInitProbes", []):
            check(probe.get("passed"), f'{probe["screen"]}: native initialization failed')
        for transfer in containers.get("transferChecks", []):
            check(transfer.get("passed"), f'{transfer["direction"]}: item conservation failed')
        trace = containers.get("layoutTrace", [])
        heading_changes = [event for event in trace if event.get("text") == "Treasury Items:"]
        check(len(heading_changes) <= 1, "bank storage heading keeps changing layout after settling")
        for capture in containers.get("captures", []):
            scrollbars(capture)
            check(capture.get("allSlotsBound"), f'{capture["name"]}: unbound menu slots')
            check(capture.get("positiveWindowArea"), f'{capture["name"]}: empty window')
            check(capture["left"] >= -1 and capture["left"] + capture["windowWidth"] <= capture["guiWidth"] + 1,
                  f'{capture["name"]}: window outside horizontal viewport')
            for slot in capture.get("slots", []):
                if slot.get("displayed"):
                    check(slot["x"] >= -1 and slot["x"] + slot["width"] <= capture["guiWidth"] + 1
                          and slot["y"] >= -1 and slot["y"] + slot["height"] <= capture["guiHeight"] + 1,
                          f'{capture["name"]}: slot {slot["slotIndex"]} outside viewport')
                    if "contentX" in slot:
                        check(abs(slot["vanillaX"] + capture["left"] - slot["contentX"]) <= 1
                              and abs(slot["vanillaY"] + capture["top"] - slot["contentY"]) <= 1,
                              f'{capture["name"]}: slot {slot["slotIndex"]} click coordinates disagree with UI')
            for element in capture.get("visibleInputs", []):
                check(element["width"] >= 40, f'{capture["name"]}: input collapsed')
                check(element["y"] >= -1 and element["y"] + element["height"] <= capture["guiHeight"] + 1,
                      f'{capture["name"]}: input outside vertical viewport')
            for result in capture.get("layoutChecks", []):
                check(result.get("passed"), f'{capture["name"]}: {result["name"]}')

    check(market_file.exists() or container_file.exists(), "no native output found")
    for failure in failures:
        print("FAIL:", failure)
    print(f"{checks} native checks; {len(failures)} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

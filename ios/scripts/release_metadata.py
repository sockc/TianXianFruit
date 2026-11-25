import re
from pathlib import Path

root = Path(__file__).resolve().parents[2]
project = (root / "ios/project.yml").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle").read_text(encoding="utf-8")

def one(pattern, source):
    values = re.findall(pattern, source, re.M)
    assert len(values) == 1, (pattern, values)
    return values[0]

ios_version = one(r'^\s*MARKETING_VERSION:\s*"(\d+\.\d+\.\d+)"\s*$', project)
ios_build = one(r'^\s*CURRENT_PROJECT_VERSION:\s*"(\d+)"\s*$', project)
android_version = one(r'^\s*versionName\s+"(\d+\.\d+\.\d+\.\d+)"\s*$', gradle)
android_artifact = "TianXianFruit-v" + android_version
ios_artifact = "TianXianFruit-iOS-v" + ios_version + "-Build" + ios_build + "-TrollStore"
for key, value in {
    "ios_version": ios_version, "ios_build": ios_build, "android_version": android_version,
    "android_artifact": android_artifact, "ios_artifact": ios_artifact,
    "apk": android_artifact + ".apk", "ipa": ios_artifact + ".ipa",
    "joint_artifact": "TianXianFruit-joint-v" + android_version + "-iOS-v" + ios_version + "-Build" + ios_build,
}.items():
    print(f"{key}={value}")

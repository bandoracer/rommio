#!/usr/bin/env python3
"""Destructive capability tests restricted to this repository's disposable Compose stack."""
import argparse
import concurrent.futures
import hashlib
import http.client
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:18765"
CONTAINER = "rommio-protocol-test-romm-1"
SCOPES = "me.read assets.read assets.write users.read users.write roms.read platforms.read"
anonymous_csrf = None


def request(path, token=None, method="GET", data=None, headers=None):
    global anonymous_csrf
    headers = dict(headers or {})
    if token:
        headers["Authorization"] = "Bearer " + token
    elif anonymous_csrf:
        headers["Cookie"] = "romm_csrftoken=" + anonymous_csrf
        headers["X-CSRFToken"] = anonymous_csrf
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            if not token and response.headers.get("Set-Cookie"):
                cookies = SimpleCookie(response.headers["Set-Cookie"])
                if "romm_csrftoken" in cookies:
                    anonymous_csrf = cookies["romm_csrftoken"].value
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as response:
        return response.code, response.read(), dict(response.headers)


def api(path, token=None, method="GET", value=None):
    data = json.dumps(value).encode() if value is not None else None
    status, payload, _ = request(path, token, method, data, {"Content-Type": "application/json"})
    if not 200 <= status < 300:
        raise RuntimeError(f"{method} {path}: HTTP {status}")
    return json.loads(payload)


def multipart(name, payload):
    boundary = "rommio" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="stateFile"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
            + payload + f"\r\n--{boundary}--\r\n".encode())
    return body, {"Content-Type": "multipart/form-data; boundary=" + boundary}


def token_for(username, password):
    data = urllib.parse.urlencode({"grant_type": "password", "username": username, "password": password, "scope": SCOPES}).encode()
    status, body, _ = request("/api/token", method="POST", data=data, headers={"Content-Type": "application/x-www-form-urlencoded"})
    if status != 200:
        raise RuntimeError(f"Test-account login failed: HTTP {status}")
    return json.loads(body)["access_token"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker-context", default="colima-rommio-test")
    parser.add_argument("--credential-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    docker = ["docker", "--context", args.docker_context]
    inspected = json.loads(subprocess.check_output(docker + ["inspect", CONTAINER]))[0]
    assert inspected["Config"]["Labels"]["com.docker.compose.project"] == "rommio-protocol-test"
    assert inspected["NetworkSettings"]["Ports"]["8080/tcp"] == [{"HostIp": "127.0.0.1", "HostPort": "18765"}]
    assert all(mount["Type"] == "volume" for mount in inspected["Mounts"]), "Refusing a server with host bind mounts"
    heartbeat = api("/api/heartbeat")
    assert heartbeat["SYSTEM"]["VERSION"] == "5.2.0"

    if args.credential_file.exists():
        credentials = json.loads(args.credential_file.read_text())
    else:
        credentials = {name: secrets.token_hex(24) for name in ["probe_admin_a", "probe_admin_b"]}
        fd = os.open(args.credential_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(credentials, stream)
    a_name, b_name = credentials.keys()
    if heartbeat["SYSTEM"]["SHOW_SETUP_WIZARD"]:
        api("/api/users", method="POST", value={"username": a_name, "email": "", "password": credentials[a_name], "role": "admin"})
    a = token_for(a_name, credentials[a_name])
    users = api("/api/users", a)
    if not any(user["username"] == b_name for user in users):
        api("/api/users", a, "POST", {"username": b_name, "email": "", "password": credentials[b_name], "role": "admin"})
    b = token_for(b_name, credentials[b_name])

    seeded = subprocess.check_output(docker + ["exec", "-i", "-w", "/backend", CONTAINER, "/src/.venv/bin/python", "-"], input=Path(__file__).with_name("seed_fixture.py").read_bytes())
    fixture = json.loads(seeded.decode().splitlines()[-1])
    rom = fixture["rom_id"]
    prefix = "__rommio_v2_probe_" + uuid.uuid4().hex
    path = f"/api/states?rom_id={rom}&emulator=rommio-v2-probe"

    def upload(name, payload, owner=a, extra_headers=None):
        body, headers = multipart(name, payload)
        headers.update(extra_headers or {})
        status, result, _ = request(path, owner, "POST", body, headers)
        assert status == 200, (status, result[:200])
        state = json.loads(result)
        assert state["file_name"] == name, "Server changed the operation filename"
        return state

    def inventory(owner=a):
        values = api(f"/api/states?rom_id={rom}", owner)
        assert isinstance(values, list)
        return [entry for entry in values if entry["file_name"].startswith(prefix)]

    def readback(state, payload, owner=a):
        status, result, _ = request(f'/api/states/{state["id"]}/content', owner)
        assert status == 200 and result == payload
        assert state["file_size_bytes"] == len(payload)

    blob = bytes(range(256))
    blob_name = prefix + "_blob_" + hashlib.sha256(blob).hexdigest() + ".bin"
    artifact = upload(blob_name, blob)
    readback(artifact, blob)
    operation_name = prefix + "_operation_" + uuid.uuid4().hex + ".json"
    manifest = json.dumps({"schemaVersion": 2, "probeOnly": True, "artifact": blob_name, "sha256": hashlib.sha256(blob).hexdigest()}, sort_keys=True).encode()
    operation = upload(operation_name, manifest)
    readback(operation, manifest)
    retried = upload(operation_name, manifest)
    assert retried["id"] == operation["id"]
    assert len([v for v in inventory() if v["file_name"] == operation_name]) == 1

    assert inventory(b) == []
    assert request(f'/api/states/{operation["id"]}/content', b)[0] == 404
    update_body, update_headers = multipart(operation_name, b"foreign edit")
    assert request(f'/api/states/{operation["id"]}', b, "PUT", update_body, update_headers)[0] == 404
    foreign = upload(operation_name, b"other account", b)
    assert foreign["id"] != operation["id"]
    readback(operation, manifest)

    # Distinct append-style records, including concurrent publication and >50 inventory entries.
    payloads = [(prefix + f"_concurrent_{index:03d}.json", json.dumps({"writer": index}).encode()) for index in range(55)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        states = list(pool.map(lambda value: upload(*value), payloads))
    names = {state["file_name"] for state in inventory()}
    assert all(name in names for name, _ in payloads)
    for state, (_, payload) in zip(states, payloads):
        readback(state, payload)

    # Same-name mutable writes do not have compare-and-swap semantics.
    canary_name = prefix + "_conditional_canary.json"
    canary = upload(canary_name, b"before")
    updated = upload(canary_name, b"after", extra_headers={"If-Match": '"deliberately-stale"'})
    assert updated["id"] == canary["id"]
    readback(updated, b"after")
    put_body, put_headers = multipart(canary_name, b"after-put")
    put_headers["If-Match"] = '"deliberately-stale"'
    put_status, _, _ = request(f'/api/states/{canary["id"]}', a, "PUT", put_body, put_headers)
    assert put_status == 200

    # Send an operation without reading its response. Discover before retrying the same payload.
    ambiguous_name = prefix + "_lost_response.json"
    ambiguous_data = b'{"probe":"response-not-observed"}'
    body, headers = multipart(ambiguous_name, ambiguous_data)
    headers["Authorization"] = "Bearer " + a
    connection = http.client.HTTPConnection("127.0.0.1", 18765, timeout=10)
    connection.request("POST", path, body=body, headers=headers)
    discovered = None
    try:
        # Keep the request connection alive until an independent reader proves commit,
        # then discard the connection without reading its success response.
        for _ in range(20):
            discovered = next((s for s in inventory() if s["file_name"] == ambiguous_name), None)
            if discovered:
                break
            time.sleep(0.25)
    finally:
        connection.close()
    assert discovered is not None, "Dropped-response upload was not discoverable"
    readback(discovered, ambiguous_data)
    retry = upload(ambiguous_name, ambiguous_data)
    assert retry["id"] == discovered["id"]
    readback(retry, ambiguous_data)

    image = json.loads(subprocess.check_output(docker + ["image", "inspect", inspected["Image"]]))[0]
    report = {
        "server_version": "5.2.0", "server_source_commit": "42e8043372522089a3bd724e8f4a0635aa1c6bf9",
        "image_digest": image["RepoDigests"], "executed_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "isolated_stack": "rommio-protocol-test", "synthetic_rom_id": rom,
        "passed": ["opaque JSON state manifests", "binary hash readback", "unique filename preservation",
                   "same-operation sequential retry", "cross-account list/read/update isolation", "same-name account isolation",
                   "55 concurrent unique records retained and read back", "inventory exceeds default catalog page size",
                   "upload with unread response discovered and retried"],
        "conditional_post_supported": False, "conditional_put_supported": False,
        "selected_transport": "immutable operation inventory over private states",
        "limitations": ["Server permits same-name overwrites; immutability must be enforced by protocol-v2 clients and payload verification",
                        "Only RomM 5.2.0 qualified by this probe", "No remote garbage collection qualified",
                        "Finite concurrency test does not guarantee global linearizability", "Client protocol graph/recovery fixtures still required"],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

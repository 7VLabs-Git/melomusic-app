#!/usr/bin/env python3
import requests

# Test ping
resp = requests.get("http://localhost:8000/api/ping")
print(f"Ping Status: {resp.status_code}")
print(f"Ping Response: {resp.json()}")

# Test registration with better error handling
try:
    resp2 = requests.post("http://localhost:8000/api/auth/register", json={
        "email": "test@example.com",
        "password": "SecurePass123!",
        "display_name": "Test User"
    })
    print(f"\nReg Status: {resp2.status_code}")
    try:
        print(f"Reg Response JSON: {resp2.json()}")
    except:
        print(f"Reg Response Text: {resp2.text}")
except Exception as e:
    print(f"Error: {e}")

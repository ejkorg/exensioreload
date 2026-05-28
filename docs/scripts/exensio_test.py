#!/usr/bin/env python3

"""
Exensio Production API Test Script
Usage: python3 exensio_test.py [lot_id] [wafer_id]
"""

import requests
import json
import sys
import argparse
from typing import Optional, Dict, Any

# Configuration
EXENSIO_PROD_URL = "https://api-prod.canyon.aws.pdf.com/api/v1/"
EXENSIO_USERNAME = "YQS_API_USER"
EXENSIO_PASSWORD = "xNsqy667p"
EXENSIO_DBNAME = "PROD"
EXENSIO_DBSCHEMA = "PRODUCTION"

class ExensioClient:
    def __init__(self, base_url: str, username: str, password: str):
        self.base_url = base_url.rstrip('/')
        self.username = username
        self.password = password
        self.token: Optional[str] = None
        self.session = requests.Session()
        self.session.headers.update({'Content-Type': 'application/json'})
    
    def login(self) -> bool:
        """Authenticate and get token"""
        try:
            payload = {
                "username": self.username,
                "password": self.password,
                "dbname": EXENSIO_DBNAME,
                "dbschema": EXENSIO_DBSCHEMA
            }

            # Support both base URL styles:
            # 1) .../api/v1  -> /session/login
            # 2) .../api     -> /v1/session/login
            login_paths = ["/session/login", "/v1/session/login"] if self.base_url.endswith("/v1") else ["/v1/session/login", "/session/login"]

            for path in login_paths:
                url = f"{self.base_url}{path}"
                print(f"🔐 Logging in to {url}...")
                response = self.session.post(url, json=payload, timeout=10, verify=False)

                if response.status_code != 200:
                    print(f"⚠️  Login attempt failed with status {response.status_code}")
                    print(f"   Response: {response.text[:200]}")
                    continue

                try:
                    data = response.json()
                except Exception:
                    print("⚠️  Login returned non-JSON response on this path")
                    print(f"   Raw response: {response.text[:200]}")
                    continue

                self.token = data.get('token') or data.get('access_token')
                if self.token:
                    print(f"✅ Login successful")
                    print(f"   Token: {self.token[:50]}...")
                    self.session.headers.update({'Authorization': f'Bearer {self.token}'})
                    return True

                print(f"⚠️  No token field in login response: {data}")

            print("❌ Login failed on all known login paths")
            return False
        except Exception as e:
            print(f"❌ Login error: {e}")
            return False
    
    def lot_wafer_lookup(self, lot_id: str, wafer_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """Lookup lot and wafer in Exensio"""
        if not self.token:
            print("❌ Not authenticated. Call login() first")
            return None
        
        try:
            url = f"{self.base_url}/key/lot-wafer-lookup"
            
            wafer_is_blank = (not wafer_id) or (wafer_id.lower() in {"none", "null", ""})
            pgc_key = 2 if wafer_is_blank else 1

            # Build payload - pgc_key=2 when wafer is null/blank, otherwise pgc_key=1
            payload = {
                "pgc_key": pgc_key,
                "lot_ids": [lot_id]
            }
            
            # Only add wafer_ids if provided and not null
            if not wafer_is_blank:
                payload["wafer_ids"] = [wafer_id]
            else:
                payload["wafer_ids"] = []
            
            print(f"🔍 Looking up lot/wafer...")
            print(f"   Lot: {lot_id}")
            print(f"   Wafer: {wafer_id if wafer_id else '(none)'}")
            print(f"   pgc_key: {pgc_key}")
            
            response = self.session.post(url, json=payload, timeout=15, verify=False)
            
            if response.status_code == 200:
                data = response.json()
                status = data.get('status', 'UNKNOWN')
                print(f"✅ Response status: {status}")
                return data
            else:
                print(f"❌ Lookup failed with status {response.status_code}")
                print(f"   Response: {response.text}")
                return None
        except Exception as e:
            print(f"❌ Lookup error: {e}")
            return None
    
    def get_lot_details(self, lot_id: str) -> Optional[Dict[str, Any]]:
        """Get detailed information about a lot"""
        if not self.token:
            print("❌ Not authenticated. Call login() first")
            return None
        
        try:
            url = f"{self.base_url}/lots/{lot_id}"
            
            print(f"📋 Fetching lot details for {lot_id}...")
            response = self.session.get(url, timeout=15, verify=False)
            
            if response.status_code == 200:
                data = response.json()
                print(f"✅ Lot details retrieved")
                return data
            elif response.status_code == 404:
                print(f"❌ Lot {lot_id} not found (404)")
                return None
            else:
                print(f"❌ Failed to fetch lot details with status {response.status_code}")
                print(f"   Response: {response.text}")
                return None
        except Exception as e:
            print(f"❌ Error fetching lot details: {e}")
            return None


def print_section(title: str):
    """Print a formatted section header"""
    print("\n" + "━" * 60)
    print(f"  {title}")
    print("━" * 60)


def print_lookup_result(data: Dict[str, Any]):
    """Pretty print lot-wafer lookup result"""
    if not data:
        return
    
    status = data.get('status', 'UNKNOWN')
    lots = data.get('lots', [])
    
    if status == "SUCCESS" and lots:
        print("\n✅ Lot found in Exensio!")
        for lot in lots:
            lot_id = lot.get('lot_id', 'N/A')
            wafers = lot.get('wafers', [])
            print(f"\n  Lot: {lot_id}")
            if wafers:
                for wafer in wafers:
                    print(f"    └─ Wafer: {wafer.get('wafer_id', 'N/A')}")
                    print(f"       State: {wafer.get('state', 'N/A')}")
                    print(f"       Location: {wafer.get('location', 'N/A')}")
                    print(f"       Loaded At: {wafer.get('loaded_at', 'N/A')}")
            else:
                print(f"    └─ (no wafers found)")
    elif status == "NOT_FOUND":
        print("\n❌ Lot NOT found in Exensio")
    else:
        print(f"\n⚠️  Unexpected status: {status}")
    
    print(f"\n  Full response:")
    print(json.dumps(data, indent=2))


def print_lot_details(data: Dict[str, Any]):
    """Pretty print lot details"""
    if not data:
        return
    
    lot_id = data.get('lot_id', 'N/A')
    status = data.get('status', 'N/A')
    load_date = data.get('load_date', 'N/A')
    wafer_count = data.get('wafer_count', 'N/A')
    part_number = data.get('part_number', 'N/A')
    
    print(f"\n  Lot ID: {lot_id}")
    print(f"  Status: {status}")
    print(f"  Load Date: {load_date}")
    print(f"  Wafer Count: {wafer_count}")
    print(f"  Part Number: {part_number}")
    
    print(f"\n  Full response:")
    print(json.dumps(data, indent=2))


def main():
    parser = argparse.ArgumentParser(
        description='Test Exensio Production API',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  python3 exensio_test.py S7U180015
  python3 exensio_test.py S7U180015 W001
  python3 exensio_test.py S7U180015 -w W001
        '''
    )
    
    parser.add_argument('lot', nargs='?', default='S7U180015',
                        help='Lot ID (default: S7U180015)')
    parser.add_argument('-w', '--wafer', dest='wafer', default=None,
                        help='Wafer ID (optional)')
    parser.add_argument('-u', '--url', dest='url', default=EXENSIO_PROD_URL,
                        help=f'Exensio API URL (default: {EXENSIO_PROD_URL})')
    
    args = parser.parse_args()
    
    # Handle case where wafer is passed as positional argument
    lot_id = args.lot
    wafer_id = args.wafer
    
    # If 2 positional args, treat as lot and wafer
    if len(sys.argv) > 2 and not sys.argv[2].startswith('-'):
        wafer_id = sys.argv[2]
    
    print_section("Exensio Production API Test")
    print(f"\n  URL: {args.url}")
    print(f"  Lot: {lot_id}")
    print(f"  Wafer: {wafer_id if wafer_id else '(none)'}")
    
    # Create client and login
    client = ExensioClient(args.url, EXENSIO_USERNAME, EXENSIO_PASSWORD)
    
    if not client.login():
        print("\n❌ Failed to authenticate with Exensio")
        sys.exit(1)
    
    # Perform lot-wafer lookup
    print_section("Lot/Wafer Lookup")
    lookup_result = client.lot_wafer_lookup(lot_id, wafer_id)
    if lookup_result:
        print_lookup_result(lookup_result)
    
    # Get lot details if lookup was successful
    if lookup_result and lookup_result.get('status') == 'SUCCESS':
        print_section("Lot Details")
        details = client.get_lot_details(lot_id)
        if details:
            print_lot_details(details)
    
    print_section("Test Complete")
    print()


if __name__ == '__main__':
    # Suppress SSL warnings for local testing
    import urllib3
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
    
    main()

#!/usr/bin/env python3
"""Write the RFW-12316 scenario folders from the existing universe item shape."""

import json
import os

ROOT = os.path.join(
    os.path.dirname(__file__),
    "..",
    "src",
    "main",
    "resources",
    "scenario-samples",
    "catalog",
    "rfw-12316",
)
BASE = os.path.join(ROOT, "01-pending.universe.json")


def universe(path, banner, item, parent, reg, size_code, primary_size):
    with open(BASE, encoding="utf-8") as handle:
        doc = json.load(handle)
    doc["Banner"] = banner
    doc["ItemCode"] = item
    doc["ParentProductCode"] = parent
    doc["RegClassCode"] = reg
    doc["SizeCode"] = size_code
    doc["PrimarySize"] = primary_size
    doc["SizeDescription"] = primary_size + " Regular"
    write(path, doc)


def enriched(path, product_id, brand, short, long, image):
    write(path, {
        "productId": product_id,
        "brand": brand,
        "shortDescription": short,
        "longDescription": long,
        "onlineExclusive": "N",
        "defaultColor": "N",
        "assetNames": image,
    })


def price(path, list_name, sku, amount):
    write(path, {
        "PriceListName": list_name,
        "CatentryPartNumber": sku,
        "Precedence": 0,
        "StartDate": "2026-03-24 00:00:00.000000000",
        "EndDate": "",
        "QuantityUnitIdentifier": "C62",
        "MinimumQuantity": 1,
        "MaximumQuantity": "",
        "LastUpdate": "2026-03-24 00:00:00.000000000",
        "PriceinUSD": amount,
        "Delete": 0,
        "_DeltaStatus": str(amount),
    })


def promo(path, skus):
    write(path, {
        "skus": [
            {
                "partNumber": sku,
                "isSale": True,
                "prices": {
                    "TMWCASListPrice": 249.99,
                    "TMWSalePrice": 199.99,
                    "TMWCASPrice": 179.99,
                },
            }
            for sku in skus
        ]
    })


def write(path, doc):
    target = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(doc, handle, indent=2)
        handle.write("\n")


def main():
    universe("s1/sku-a.universe.json", "TMW", "TMWR1AA15", "TMW_R1A", "R1A", "440", "44")
    universe("s1/sku-b.universe.json", "TMW", "TMWR1AB15", "TMW_R1A", "R1A", "441", "46")
    enriched(
        "s1/complete.enriched.json",
        "TMW_R1A_15",
        "TMW",
        "Catalog Utility complete jacket",
        "Both mandatory enrichment fields are present for R1A.",
        "TMW_R1A_15_MAIN.jpg",
    )
    price("s1/sku-a.list.json", "TMWCASList", "TMWR1AA15", 249.99)
    price("s1/sku-a.sale.json", "TMWSalePrice", "TMWR1AA15", 199.99)
    price("s1/sku-b.list.json", "TMWCASList", "TMWR1AB15", 249.99)
    price("s1/sku-b.sale.json", "TMWSalePrice", "TMWR1AB15", 199.99)
    promo("s1/promo.json", ["TMWR1AA15", "TMWR1AB15"])

    universe("s2/sku.universe.json", "TMW", "TMWR2AA15", "TMW_R2A", "R2A", "440", "44")
    enriched(
        "s2/incomplete.enriched.json",
        "TMW_R2A_15",
        "TMW",
        "Catalog Utility incomplete jacket",
        " ",
        "TMW_R2A_15_MAIN.jpg",
    )
    price("s2/sku.list.json", "TMWCASList", "TMWR2AA15", 249.99)
    price("s2/sku.sale.json", "TMWSalePrice", "TMWR2AA15", 199.99)
    promo("s2/promo.json", ["TMWR2AA15"])

    universe("s2b/sku.universe.json", "TMW", "TMWR2BA15", "TMW_R2B", "R2B", "440", "44")
    enriched(
        "s2b/image-blank.enriched.json",
        "TMW_R2B_15",
        "TMW",
        "Catalog Utility jacket without an image",
        "Name and description are present, so enrichment is complete, but there is no image.",
        "",
    )

    universe("s3/merged.universe.json", "TMW", "TMWR3AA15", "TMW_OLD3", "R3A", "440", "44")
    universe("s3/active.universe.json", "TMW", "TMWR3BA15", "TMW_R3B", "R3B", "440", "44")
    universe("s3/merged-sibling.universe.json", "TMW", "TMWR3BB15", "TMW_BT3B", "R3B", "441", "46")

    universe("s4/sku.universe.json", "TMW", "TMWR4AA15", "TMW_R4A", "R4A", "440", "44")
    enriched(
        "s4/complete.enriched.json",
        "TMW_R4A_15",
        "TMW",
        "Catalog Utility complete jacket",
        "Both mandatory enrichment fields are present for R4A.",
        "TMW_R4A_15_MAIN.jpg",
    )
    enriched(
        "s4/revise.enriched.json",
        "TMW_R4A_15",
        "TMW",
        "Catalog Utility revised jacket name",
        "Revised description after the item was already complete.",
        "TMW_R4A_15_REVISE_MAIN.jpg",
    )

    universe("s5/sku.universe.json", "kng", "KNGR5AA15", "KNG_R5A", "R5A", "440", "44")
    enriched(
        "s5/complete.enriched.json",
        "KNG_R5A_15",
        "KNG",
        "Catalog Utility complete jacket",
        "Both mandatory enrichment fields are present for R5A.",
        "KNG_R5A_15_MAIN.jpg",
    )

    universe("s6/missing-banner.universe.json", "", "TMWR6AA15", "TMW_R6A", "R6A", "440", "44")
    print("wrote RFW-12316 scenario folders")


if __name__ == "__main__":
    main()

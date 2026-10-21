"""Prevent regression of Alipay 经营码交易 parsing and same-file backfill."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
PARSER=(ROOT/"app/src/main/java/com/tianxian/fruit/data/PaymentBillParser.kt").read_text(encoding="utf8")
DB=(ROOT/"app/src/main/java/com/tianxian/fruit/data/AppDatabase.kt").read_text(encoding="utf8")

class AlipayMerchantCode112Test(unittest.TestCase):
    def test_merchant_code_is_narrow_alipay_income_only(self):
        self.assertIn('product.trim() == "经营码交易"',PARSER)
        self.assertIn('platform == PaymentBillPlatform.ALIPAY',PARSER)
        self.assertIn('direction.trim() == "收入"',PARSER)
        self.assertIn('STRICT_RECEIPT_PHRASES',PARSER)
        self.assertIn('isRejectedStatus(',PARSER)

    def test_same_file_reimport_appends_missing_receipts_without_overwriting(self):
        importer=DB.split("fun importCustomerPaymentBill(",1)[1].split("private data class OriginalCustomerPayment",1)[0]
        existing=importer.split("if (existing != null) {",1)[1].split("\n        db.beginTransaction()",1)[0]
        self.assertIn('var inserted = 0',existing)
        self.assertIn('"customer_payment_transaction"',existing)
        self.assertIn('SQLiteDatabase.CONFLICT_IGNORE',existing)
        self.assertIn('"customer_payment_source_link"',existing)
        self.assertIn('insertedRows = inserted',existing)
        self.assertIn('put("accepted_rows"',existing)
        self.assertIn('put("observed_receipt_rows"',existing)
        self.assertIn('db.setTransactionSuccessful()',existing)
        self.assertIn('db.endTransaction()',existing)

if __name__=="__main__":
    unittest.main()

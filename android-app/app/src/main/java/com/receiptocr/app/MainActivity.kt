package com.receiptocr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.receiptocr.app.ui.ReceiptOCRApp
import com.receiptocr.app.ui.theme.ReceiptOCRTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReceiptOCRTheme {
                ReceiptOCRApp()
            }
        }
    }
}

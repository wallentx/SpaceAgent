package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.example.ui.parseOutputBlocks

@Composable
fun SpaceOutputView(output: String) {
    val blocks = remember(output) { parseOutputBlocks(output) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            if (block.isCode) CodeBlockView(block.text)
            else Text(block.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

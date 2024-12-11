package org.readium.r2.testapp.reader.preferences

import android.app.Dialog
import android.content.res.Resources
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.pageList
import org.readium.r2.testapp.reader.ReaderViewModel
import org.readium.r2.testapp.utils.compose.ComposeBottomSheetDialogFragment

class MissingBottomSheetDialogFragment(
    private val missingXhtml: List<String>,
) : ComposeBottomSheetDialogFragment(
    isScrollable = true
) {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            // Reduce the dim to see the impact of the settings on the page.
            window?.setDimAmount(0.1f)

            behavior.apply {
                peekHeight = Resources.getSystem().displayMetrics.heightPixels
                maxHeight = Resources.getSystem().displayMetrics.heightPixels
            }
        }

    @Composable
    override fun Content() {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Button(onClick = { this@MissingBottomSheetDialogFragment.dismiss() }) {
                Text("Go Back")
            }
            Spacer(Modifier.height(16.dp))
            Text("Missing XHTML files")
            Spacer(Modifier.height(16.dp))
            missingXhtml.forEach {
                Text(it)
            }
        }
    }
}
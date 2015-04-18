/*
 * Copyright (C) 2015 The Oneplus Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui;

import static com.android.documentsui.DocumentsActivity.TAG;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.android.documentsui.model.DocumentInfo;

import java.io.FileNotFoundException;

/**
 * Dialog to rename a document.
 */
public class RenameDocumentFragment extends DialogFragment {
    private static final String TAG_RENAME_DOCUMENT = "rename_document";

    public static void show(FragmentManager fm, DocumentInfo documentInfo) {
        final RenameDocumentFragment dialog = new RenameDocumentFragment();
        dialog.mDisplayName = documentInfo.displayName;
        dialog.mUri = documentInfo.derivedUri;
        dialog.show(fm, TAG_RENAME_DOCUMENT);
    }

    private String mDisplayName;
    private Uri mUri;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mDisplayName = savedInstanceState.getString("displayName");
            mUri = savedInstanceState.getParcelable("uri");
        }
        final Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        final LayoutInflater dialogInflater = LayoutInflater.from(builder.getContext());

        final View view = dialogInflater.inflate(R.layout.dialog_rename_doc, null, false);
        final EditText text1 = (EditText) view.findViewById(android.R.id.text1);
        text1.setText(mDisplayName);
        text1.setFilters(new InputFilter[] { new FolderInputFilter() });

        builder.setTitle(R.string.menu_rename_doc);
        builder.setView(view);

        builder.setPositiveButton(android.R.string.ok, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final String displayName = text1.getText().toString();

                final DocumentsActivity activity = (DocumentsActivity) getActivity();

                if (displayName == null || "".equals(displayName.trim())) {
                    Toast.makeText(activity, activity.getResources().getString(
                            R.string.document_name_empty, displayName), Toast.LENGTH_LONG).show();
                    return;
                }

                final DocumentInfo cwd = activity.getCurrentDirectory();

                boolean exists = DocumentsContract.isChildDocument(resolver, cwd.derivedUri,
                        displayName);
                if (exists) {
                    Toast.makeText(activity, activity.getResources().getString(
                            R.string.document_exists, displayName), Toast.LENGTH_LONG).show();
                } else {
                    new RenameDocumentTask(activity, mUri, displayName).executeOnExecutor(
                            ProviderExecutor.forAuthority(cwd.authority));
                }
                ((DocumentsActivity) context).removeDialog((Dialog) dialog);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ((DocumentsActivity) context).removeDialog((Dialog) dialog);
            }
        });

        AlertDialog dialog = builder.create();
        ((DocumentsActivity) context).addDialog((Dialog) dialog);
        return dialog;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putString("displayName", mDisplayName);
        outState.putParcelable("uri", mUri);
        super.onSaveInstanceState(outState);
    }

    private class RenameDocumentTask extends AsyncTask<Void, Void, DocumentInfo> {
        private final DocumentsActivity mActivity;
        private final Uri mUri;
        private final String mDisplayName;

        public RenameDocumentTask(
                DocumentsActivity activity, Uri uri, String displayName) {
            mActivity = activity;
            mUri = uri;
            mDisplayName = displayName;
        }

        @Override
        protected void onPreExecute() {
            mActivity.setPending(true);
        }

        @Override
        protected DocumentInfo doInBackground(Void... params) {
            final ContentResolver resolver = mActivity.getContentResolver();
            ContentProviderClient client = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, mUri.getAuthority());
                final Uri childUri = DocumentsContract.renameDocument(
                        client, mUri, mDisplayName);
                return DocumentInfo.fromUri(resolver, childUri);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to rename file", e);
                return null;
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to rename file", e);
                return null;
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            mActivity.setPending(false);
        }
    }
}

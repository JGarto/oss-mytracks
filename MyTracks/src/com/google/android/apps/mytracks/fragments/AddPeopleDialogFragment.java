/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.mytracks.fragments;

import com.google.android.apps.mytracks.io.sendtogoogle.AccountChooserActivity;
import com.google.android.apps.mytracks.io.sendtogoogle.SendRequest;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.maps.mytracks.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.View;
import android.widget.FilterQueryProvider;
import android.widget.MultiAutoCompleteTextView;

/**
 * A DialogFragment to add people to share a track with.
 * 
 * @author Jimmy Shih
 */
public class AddPeopleDialogFragment extends DialogFragment {

  public static final String ADD_PEOPLE_DIALOG_TAG = "addPeopleDialog";

  private static final String KEY_TRACK_ID = "trackId";

  public static AddPeopleDialogFragment newInstance(long trackId) {
    Bundle bundle = new Bundle();
    bundle.putLong(KEY_TRACK_ID, trackId);

    AddPeopleDialogFragment addPeopleDialogFragment = new AddPeopleDialogFragment();
    addPeopleDialogFragment.setArguments(bundle);
    return addPeopleDialogFragment;
  }

  private MultiAutoCompleteTextView multiAutoCompleteTextView;

  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    FragmentActivity activity = getActivity();
    View view = activity.getLayoutInflater().inflate(R.layout.add_people, null);
    multiAutoCompleteTextView = (MultiAutoCompleteTextView) view.findViewById(R.id.add_people);
    multiAutoCompleteTextView.setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer());

    SimpleCursorAdapter adapter = new SimpleCursorAdapter(getActivity(), R.layout.add_people_item,
        getCursor(getActivity(), null), new String[] {
            ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.DATA },
        new int[] { android.R.id.text1, android.R.id.text2 }, 0);
    adapter.setCursorToStringConverter(new SimpleCursorAdapter.CursorToStringConverter() {
        @Override
      public CharSequence convertToString(Cursor cursor) {
        int index = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA);
        return cursor.getString(index).trim();
      }
    });
    adapter.setFilterQueryProvider(new FilterQueryProvider() {
        @Override
      public Cursor runQuery(CharSequence constraint) {
        return getCursor(getActivity(), constraint);
      }
    });
    multiAutoCompleteTextView.setAdapter(adapter);

    return new AlertDialog.Builder(activity).setNegativeButton(R.string.generic_cancel, null)
        .setPositiveButton(R.string.generic_ok, new DialogInterface.OnClickListener() {
            @Override
          public void onClick(DialogInterface dialog, int which) {
            String acl = multiAutoCompleteTextView.getText().toString();
            if (acl != null && !acl.equals("")) {
              long trackId = getArguments().getLong(KEY_TRACK_ID);
              SendRequest sendRequest = new SendRequest(trackId);
              sendRequest.setSendDrive(true);
              sendRequest.setAcl(acl);
              Intent intent = IntentUtils.newIntent(getActivity(), AccountChooserActivity.class)
                  .putExtra(SendRequest.SEND_REQUEST_KEY, sendRequest);
              startActivity(intent);
            }
          }
        }).setTitle(R.string.share_track_add_people).setView(view).create();
  }

  /**
   * Gets the cursor
   * 
   * @param activity the activity
   * @param constraint the constraint
   */
  private Cursor getCursor(Activity activity, CharSequence constraint) {
    String order = ContactsContract.Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC";
    String selection = ContactsContract.Contacts.IN_VISIBLE_GROUP + " = '1'";
    if (constraint != null) {
      selection += " AND (" + ContactsContract.Contacts.DISPLAY_NAME + " LIKE '%" + constraint
          + "%' OR " + ContactsContract.CommonDataKinds.Email.DATA + " LIKE '%" + constraint
          + "%' )";
    }
    String[] projection = new String[] { ContactsContract.Contacts._ID,
        ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.DATA };
    Uri uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI;
    return activity.getContentResolver().query(uri, projection, selection, null, order);
  }
}
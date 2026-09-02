package com.yyba.cloud.api;



import java.util.ArrayList;

import java.util.List;



public class VerifySyncResponse {

    public List<Long> invalidIds;

    public List<VerifySyncValidItem> validItems;

    public List<SyncRecordDto> rebuiltRecords;



    public VerifySyncResponse() {

        invalidIds = new ArrayList<>();

        validItems = new ArrayList<>();

        rebuiltRecords = new ArrayList<>();

    }

}



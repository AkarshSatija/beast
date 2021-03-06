package com.gojek.beast.sink.bq.handler.error;

import com.gojek.beast.sink.bq.handler.BQRecordsErrorType;
import lombok.AllArgsConstructor;

@AllArgsConstructor
/**
 * Out of bounds are caused when the partitioned column has a date value less than
 * 5 years and more than 1 year in future
 * */
public class OOBError implements ErrorDescriptor {

    private final String reason;
    private final String message;

    @Override
    public BQRecordsErrorType getType() {
        return BQRecordsErrorType.OOB;
    }

    @Override
    public boolean matches() {
        return reason.equals("invalid")
                && ((message.contains("is outside the allowed bounds") && message.contains("1825 days in the past and 366 days in the future"))
                    || message.contains("out of range"));
    }

}

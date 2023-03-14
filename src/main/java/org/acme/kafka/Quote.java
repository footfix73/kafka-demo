package org.acme.kafka;

import lombok.Data;

@Data
public class Quote   {
    String company;
    Double value;
    Double change;
    String time;

    public Quote(String company) {
        this.company = company;
    }

    public Quote() {
    }


}

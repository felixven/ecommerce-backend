package com.ecommerce.project.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddressDTO {
    private Long addressId;
    private String fullName;
    private String phoneNumber;
    private String city;
    private String district;
    private String postalCode;
    private String street;
}

package com.ecommerce.project.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "addresses")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Address {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long addressId;

    @NotBlank
    @Size(min = 2, message = "Full name must be at least 2 characters")
    private String fullName;

    @NotBlank
    @Size(min = 8, message = "Phone must be at least 8 characters")
    private String phoneNumber;

    @NotBlank
    @Size(min = 3, message = "City name must be at least 3 characters")
    private String city;

    @NotBlank
    @Size(min = 2, message = "District name must be at least 2 characters")
    private String district;

    @NotBlank
    @Size(min = 3, message = "Postal code must be at least 3 characters")
    private String postalCode;

    @NotBlank
    @Size(min = 5, message = "Street name must be at least 5 characters")
    private String street;


    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
    // @ToString.Exclude
    //@ManyToMany(mappedBy = "addresses")
    //private List<User> users = new ArrayList<>();ManyToMany的時候是list，後來改成many to one就不用list

    public Address(String fullName, String phoneNumber, String city, String district, String postalCode, String street) {
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.city = city;
        this.district = district;
        this.postalCode = postalCode;
        this.street = street;
    }
}

package com.ihbas.backend.hospital;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiryJob {
    private final BedAllocationService service;
    public ReservationExpiryJob(BedAllocationService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.reservations.expiry-check-ms:60000}")
    public void expire(){service.expireReservations();}
}

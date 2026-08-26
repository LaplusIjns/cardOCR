package com.github.laplusijns.card;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessCardRepository extends JpaRepository<BusinessCard, Long> {

    List<BusinessCard> findAllByUser_IdOrderByCreatedAtDesc(Long userId);

    java.util.Optional<BusinessCard> findByIdAndUser_Id(Long id, Long userId);

    java.util.Optional<BusinessCard> findByImageIdAndUser_Id(String imageId, Long userId);
}

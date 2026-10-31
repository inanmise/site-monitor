package com.sitemonitor.repository;

import com.sitemonitor.model.HolidayDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HolidayDayRepository extends JpaRepository<HolidayDay, String> {

    List<HolidayDay> findAllByOrderByDayAsc();
}

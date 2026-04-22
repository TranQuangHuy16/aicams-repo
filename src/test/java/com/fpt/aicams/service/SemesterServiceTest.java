package com.fpt.aicams.service;

import com.fpt.aicams.domain.Semester;
import com.fpt.aicams.dto.semester.SemesterRequest;
import com.fpt.aicams.dto.semester.UpdateRequest;
import com.fpt.aicams.exception.BadRequestException;
import com.fpt.aicams.exception.ResourceNotFoundException;
import com.fpt.aicams.repository.SemesterRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemesterServiceTest {

    @Mock
    private SemesterRepository repository;

    @InjectMocks
    private SemesterService service;

    private Semester semester;

    @BeforeEach
    void setup() {
        semester = Semester.builder()
                .id(1)
                .name("Spring 2026")
                .startDate(LocalDate.of(2026,1,1))
                .endDate(LocalDate.of(2026,6,1))
                .active(true)
                .build();
    }

    // ================= CREATE =================
    @Test
    void create_success() {

        SemesterRequest req = new SemesterRequest();
        req.setName("Spring 2026");
        req.setStartDate(LocalDate.of(2026,1,1));
        req.setEndDate(LocalDate.of(2026,6,1));

        when(repository.existsByName("Spring 2026")).thenReturn(false);
        when(repository.save(any())).thenReturn(semester);

        var res = service.create(req);

        assertEquals("Spring 2026", res.getName());
        verify(repository).save(any());
    }

    @Test
    void create_duplicateName_throw() {
        SemesterRequest req = new SemesterRequest();
        req.setName("Spring 2026");

        when(repository.existsByName("Spring 2026")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> service.create(req));
    }

    // ================= UPDATE =================
    @Test
    void update_success() {

        UpdateRequest req = new UpdateRequest();
        req.setName("Updated");
        req.setStartDate(LocalDate.of(2026,2,1));
        req.setEndDate(LocalDate.of(2026,7,1));
        req.setActive(false);

        when(repository.findById(1)).thenReturn(Optional.of(semester));
        when(repository.save(any())).thenReturn(semester);

        var res = service.update(1, req);

        assertNotNull(res);
        verify(repository).save(any());
    }

    @Test
    void update_notFound_throw() {
        when(repository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(99, new UpdateRequest()));
    }

    // ================= DELETE =================
    @Test
    void delete_success() {

        Semester semester = Semester.builder()
                .id(1)
                .name("Fall 2025")
                .active(true)
                .build();

        when(repository.findById(1)).thenReturn(Optional.of(semester));

        service.delete(1);

        assertFalse(semester.getActive());
        verify(repository).save(semester);
    }

    @Test
    void delete_notFound_throw() {

        when(repository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.delete(99));
    }


    // ================= GET ONE =================
    @Test
    void getById_success() {
        when(repository.findById(1)).thenReturn(Optional.of(semester));

        var res = service.getById(1);

        assertEquals("Spring 2026", res.getName());
    }

    @Test
    void getById_notFound_throw() {
        when(repository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getById(99));
    }

    // ================= GET ALL =================
    @Test
    void getAll_success() {

        Page<Semester> page = new PageImpl<>(List.of(semester));

        when(repository.findByActiveTrue(any())).thenReturn(page);

        var res = service.getAll(PageRequest.of(0,10), null);

        assertEquals(1, res.getItems().size());
    }

    @Test
    void getAll_empty_throw() {

        Page<Semester> page = new PageImpl<>(List.of());

        when(repository.findByActiveTrue(any())).thenReturn(page);

        assertThrows(ResourceNotFoundException.class,
                () -> service.getAll(PageRequest.of(0,10), null));
    }
}

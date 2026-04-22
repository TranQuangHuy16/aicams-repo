package com.fpt.aicams.config;

import com.fpt.aicams.domain.Role;
import com.fpt.aicams.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Initializing database with default data...");

        if (roleRepository.count() == 0) {
            initializeRoles();
            log.info("Database initialization completed successfully!");
        } else {
            log.info("Database already initialized, skipping data initialization.");
        }
    }

    private void initializeRoles() {
        createRoleIfNotExists("System Admin");
        createRoleIfNotExists("Academic Admin");
        createRoleIfNotExists("Supervisor");
        createRoleIfNotExists("Team Leader");
        createRoleIfNotExists("Student");

        log.info("Initialized 5 default roles: System Admin, Academic Admin, Supervisor, Team Leader, Student");
    }

    private void createRoleIfNotExists(String roleName) {
        if (!roleRepository.existsByName(roleName)) {
            Role role = Role.builder()
                    .name(roleName)
                    .build();
            roleRepository.save(role);
        }
    }
}

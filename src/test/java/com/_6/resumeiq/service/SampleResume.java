package com._6.resumeiq.service;

import java.util.List;

import com._6.resumeiq.dto.ExtractedResume;
import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.ResumeData.Contact;
import com._6.resumeiq.dto.ResumeData.Entry;
import com._6.resumeiq.dto.ResumeData.Link;
import com._6.resumeiq.dto.ResumeData.Section;
import com._6.resumeiq.dto.ResumeData.SkillGroup;

// A made-up student resume shared by the tests
final class SampleResume {

    static final String TEXT = """
            Jordan Lee
            jordan.lee@berkeley.edu | (510) 555-0147 | Berkeley, CA | github.com/jordanlee | linkedin.com/in/jordan-lee

            EDUCATION
            University of California, Berkeley - B.A. Computer Science, GPA 3.72 - Expected May 2027
            Relevant Coursework: CS 61B Data Structures, CS 188 Artificial Intelligence, CS 170 Algorithms

            EXPERIENCE
            Software Engineering Intern, Brightline Analytics - San Francisco, CA - Jun 2025 - Aug 2025
            - worked on backend services in Java and Spring Boot
            - Helped move reports from cron jobs to a queue using RabbitMQ, which cut report delays from 30 minutes to 5 minutes
            - wrote unit tests with JUnit for the REST API

            Undergraduate Teaching Assistant, UC Berkeley EECS - Jan 2025 - Present
            - Held office hours for 40 students each week for CS 61A
            - Graded assignments

            PROJECTS
            Extractly - Java, Spring Boot, Gemini API, AssemblyAI, JavaScript
            - Built a web app that turns meeting recordings into structured product data using Gemini
            - Integrated Zoho Creator API with OAuth2 to push extracted records
            Pac-Man AI Agents - Python
            - Implemented A* search, minimax with alpha-beta pruning and Q-learning agents for CS 188

            SKILLS
            Languages: Java, Python, JavaScript, SQL, HTML/CSS
            Frameworks & Tools: Spring Boot, React, Node.js, Git, Docker, PostgreSQL, RabbitMQ, JUnit
            """;

    private SampleResume() {
    }

    static ExtractedResume extracted() {
        return new ExtractedResume("jordan_lee_resume.txt", false, null, TEXT);
    }

    // an honest rewrite: reworded, reordered, but every fact comes from TEXT
    static ResumeData honestRewrite() {
        return new ResumeData(
                "Jordan Lee",
                null,
                new Contact("jordan.lee@berkeley.edu", "(510) 555-0147", "Berkeley, CA",
                        List.of(new Link("GitHub", "https://github.com/jordanlee"),
                                new Link("LinkedIn", "https://www.linkedin.com/in/jordan-lee/"))),
                null,
                List.of(
                        new Section("Education", "entries", List.of(
                                new Entry("University of California, Berkeley", "B.A. Computer Science, GPA 3.72",
                                        "Berkeley, CA", "Expected May 2027",
                                        List.of("Relevant coursework: CS 61B Data Structures, CS 188 Artificial Intelligence, CS 170 Algorithms"))),
                                List.of()),
                        new Section("Experience", "entries", List.of(
                                new Entry("Brightline Analytics", "Software Engineering Intern", "San Francisco, CA",
                                        "Jun 2025 - Aug 2025",
                                        List.of("Cut report delays from 30 minutes to 5 minutes by moving reports from cron jobs to a RabbitMQ queue",
                                                "Developed backend services in Java and Spring Boot",
                                                "Wrote JUnit unit tests for the REST APIs"))),
                                List.of()),
                        new Section("Projects", "entries", List.of(
                                new Entry("Extractly", "Java, Spring Boot, Gemini API, AssemblyAI, JavaScript", null, null,
                                        List.of("Built a web app that turns meeting recordings into structured product data with the Gemini API",
                                                "Integrated the Zoho Creator API using OAuth2 to push extracted records"))),
                                List.of()),
                        new Section("Skills", "skills", List.of(), List.of(
                                new SkillGroup("Languages", List.of("Java", "Python", "JavaScript", "SQL")),
                                new SkillGroup("Tools", List.of("Spring Boot", "NodeJS", "Git", "Docker", "PostgreSQL"))))),
                List.of("Moved Experience above Projects because the internship is the strongest evidence."));
    }
}

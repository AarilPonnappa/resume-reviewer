# ResumeIQ: AI Resume Reviewer

ResumeIQ reviews a resume and helps make it stronger, without making anything up.

## What it does

Upload a resume (PDF, DOCX or TXT), optionally add a target role and job description, and you get:

- **A grade**: a 0–100 score and letter grade across six categories (impact, relevance, clarity, structure, technical depth, ATS compatibility)
- **Suggestions**: specific fixes, ranked by priority
- **Bullet rewrites**: your weakest bullet points shown before and after
- **An improved resume as a PDF**: your resume restructured and rewritten using only information already in it
- **A roadmap**: projects, skills and experiences to work on next to become a stronger candidate

Reviews are saved to your account, so you can reopen them and track your progress.

## How it works

1. The uploaded file is read into text (Apache PDFBox for PDFs, a zip/XML parser for DOCX).
2. Two Gemini requests run in parallel:
   - one **grades and critiques** the resume
   - one **rewrites and restructures** it
3. The grade is **calculated in code** from the category scores using fixed weights, so the same scores always give the same grade.
4. The rewritten resume is **fact-checked** against the original: every name, date, number, technology, skill and link must appear in the original resume.
   - Anything that doesn't is sent back to Gemini to fix.
   - Whatever still can't be traced to the original is removed.
   - The page shows what was removed and why.
5. The verified resume is filled into an HTML template and converted into a PDF.

## How it's different from other AI resume tools

- **It doesn't invent experience.** Many AI resume tools will happily add metrics ("increased performance by 40%"), tools or achievements you never had. ResumeIQ checks the rewrite against your original in code, rather than just asking the AI to behave.
- **The grade is explainable.** You see the score for each category, its weight and the reason, instead of one unexplained number.
- **Advice is kept separate from the resume.** Suggestions about what to learn or build next go into a roadmap and never onto your resume.
- **Rewrites use placeholders instead of fake numbers.** Where a bullet needs a number you didn't provide, you get `[X%]` to fill in with a real one.


## APIs and tools

| Area | Used |
|---|---|
| AI | **Google Gemini API** |
| Backend | Java 21, **Spring Boot 4** (Web MVC, Data JPA, Thymeleaf, Mail), Maven |
| Frontend | HTML, CSS, vanilla JavaScript, **Bootstrap 5** |
| Database | **PostgreSQL** |
| Reading resumes | **Apache PDFBox** (PDF), Java's built-in zip and XML (StAX) parsers (DOCX) |
| Creating the PDF | **Thymeleaf** template → **openhtmltopdf** (with **jsoup**) |
| Security | **BCrypt** password hashing, session-based login, optional email verification |
| Testing | **JUnit 5**, with a fake Gemini service so tests run without network calls |
| Deployment | **Docker** (multi-stage build) and **Render** |
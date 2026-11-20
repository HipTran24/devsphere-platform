export function validateEmail(value) {
    if (!value.trim()) return "Enter your email address.";
    if (value.trim().length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim())) return "Enter a valid email address.";
    return "";
}

export function validatePassword(value) {
    if (!value) return "Enter a password.";
    if (value.length < 12) return "Use at least 12 characters.";
    return "";
}

export function validateNewPassword({ password, confirmPassword }) {
    const errors = {};
    const passwordError = validatePassword(password);
    if (passwordError) errors.password = passwordError;
    if (!confirmPassword) errors.confirmPassword = "Confirm your password.";
    else if (password !== confirmPassword) errors.confirmPassword = "Passwords don’t match.";
    return errors;
}

export function validateRegistration(values) {
    const errors = {};
    if (!values.displayName.trim()) errors.fullName = "Enter your full name.";
    else if (values.displayName.trim().length > 120) errors.fullName = "Use no more than 120 characters.";
    const emailError = !values.email.trim() ? "Enter your email." : validateEmail(values.email) ? "Enter a valid email." : "";
    if (emailError) errors.email = emailError;
    const passwordError = validatePassword(values.password);
    if (passwordError) errors.password = passwordError;
    if (!values.confirmPassword) errors.confirmPassword = "Confirm your password.";
    else if (values.password !== values.confirmPassword) errors.confirmPassword = "Passwords don’t match.";
    if (!values.terms) errors.terms = "Accept the terms to continue.";
    return errors;
}

export function registrationErrorMessage(errors) {
    const missingMessages = {
        fullName: "Enter your full name.",
        email: "Enter your email.",
        password: "Enter a password.",
        confirmPassword: "Confirm your password.",
    };
    const missing = Object.keys(missingMessages).filter(key => errors[key] === missingMessages[key]);
    if (missing.length > 1) return "Complete all required fields.";
    if (missing.length === 1) return errors[missing[0]];
    return errors.fullName || errors.email || errors.password || errors.confirmPassword || errors.terms || "";
}

export function validateLogin({ email, password }) {
    const errors = {};
    const emailError = !email.trim() ? "Enter your email." : validateEmail(email) ? "Enter a valid email." : "";
    if (emailError) errors.email = emailError;
    if (!password) errors.password = "Enter your password.";
    return errors;
}

export function loginErrorMessage(errors) {
    if (errors.email === "Enter your email." && errors.password === "Enter your password.") return "Complete all required fields.";
    if (errors.email === "Enter your email.") return errors.email;
    if (errors.password === "Enter your password.") return errors.password;
    return errors.email || errors.password || "";
}

export function focusFirstError(form, errors) {
    form.elements.namedItem(Object.keys(errors)[0])?.focus();
}

import styles from "../styles/auth.module.css";
import formStyles from "../styles/AuthForm.module.css";

export default function AuthFlowShell({ children }) {
    return <main className={styles.page}><section className={formStyles.flowCard}>{children}</section></main>;
}

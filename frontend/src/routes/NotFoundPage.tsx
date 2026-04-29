import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <main
      data-testid="not-found-page"
      className="flex min-h-screen flex-col items-center justify-center bg-surface px-6 text-center"
    >
      <h1 className="text-32 font-bold tracking-[-0.5px] text-brand-navy">Page not found</h1>
      <p className="mt-3 text-14 text-neutral-500">The page you requested doesn't exist.</p>
      <Link
        to="/"
        className="mt-6 inline-flex items-center rounded-md bg-brand-blue px-4 py-2 text-13 font-semibold text-white transition-colors hover:bg-brand-blue-hover"
      >
        Back to home
      </Link>
    </main>
  );
}

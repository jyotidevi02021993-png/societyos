import Link from "next/link";

export default function NotFound() {
  return (
    <main id="main" className="mx-auto grid max-w-md gap-3 px-4 py-24 text-center">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <p className="text-sm text-muted-foreground">The page you asked for does not exist.</p>
      <Link href="/" className="text-primary underline">
        Go to my home
      </Link>
    </main>
  );
}
